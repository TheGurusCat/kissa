// Kissa Studio: a small page on your Mac for Kissa's house writers. It lists the house accounts and what each has
// posted (with readers' comments), drafts new stories with Claude in each writer's voice, and posts the ones you
// approve through the relay's admin API (relay/src/api.js). Nothing is posted without you pressing Post.
//
// Run with `npm start` and open http://localhost:4747. Settings come from studio/.env (see .env.example).
// The writers' voices live in writers.json next to this file, so they stay on your Mac.

import { createServer } from "node:http";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import Anthropic from "@anthropic-ai/sdk";

const here = path.dirname(fileURLToPath(import.meta.url));
const WRITERS_FILE = path.join(here, "writers.json");
const PORT = Number(process.env.PORT) || 4747;
const RELAY = (process.env.KISSA_RELAY_URL || "https://kissa-relay.abpro.workers.dev").replace(/\/$/, "");
const ADMIN_KEY = process.env.KISSA_ADMIN_KEY;
const MODEL = process.env.KISSA_MODEL || "claude-opus-5-5";

const LENGTHS = {
  short: "about 150 to 300 words",
  medium: "about 400 to 800 words",
  long: "about 1,000 to 1,800 words",
};

const STORY_SCHEMA = {
  type: "object",
  properties: {
    title: { type: "string", description: "The post title, under 200 characters, written the way a person titles a Reddit post." },
    body: { type: "string", description: "The story itself, plain text with blank lines between paragraphs." },
  },
  required: ["title", "body"],
  additionalProperties: false,
};

// Made on first use, so the page still opens (to browse writers) before an Anthropic key is set.
let claude;

// --- Relay ---

async function relay(method, pathname, body, { admin = false } = {}) {
  if (admin && !ADMIN_KEY) throw new HttpError(500, "KISSA_ADMIN_KEY isn't set in studio/.env.");
  const response = await fetch(RELAY + pathname, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(admin ? { "X-Kissa-Key": ADMIN_KEY } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new HttpError(response.status, data.error || `The relay answered HTTP ${response.status}.`);
  return data;
}

// --- Writers' voices (local) ---

async function loadVoices() {
  try {
    return JSON.parse(await readFile(WRITERS_FILE, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return {};
    throw error;
  }
}

async function saveVoice(username, input) {
  const voices = await loadVoices();
  voices[username.toLowerCase()] = {
    voice: String(input.voice ?? "").trim(),
    topics: String(input.topics ?? "").trim(),
    language: input.language === "es" ? "es" : "en",
    length: LENGTHS[input.length] ? input.length : "medium",
  };
  await writeFile(WRITERS_FILE, JSON.stringify(voices, null, 2) + "\n");
  return voices[username.toLowerCase()];
}

async function listWriters() {
  const [{ users }, voices] = await Promise.all([relay("GET", "/api/users", null, { admin: true }), loadVoices()]);
  return users.map((user) => ({ ...user, ...(voices[user.username.toLowerCase()] ?? { voice: "" }) }));
}

async function createWriter(input) {
  const { username } = await relay("POST", "/api/users", { username: input.username }, { admin: true });
  await saveVoice(username, input);
  return { username };
}

// --- Drafting ---

async function draftStory(username, idea) {
  const voice = (await loadVoices())[username.toLowerCase()];
  if (!voice?.voice) throw new HttpError(400, `Give u/${username} a voice first.`);
  // Recent titles, theirs and everyone's, so a new story doesn't retell an old one.
  const [profile, feed] = await Promise.all([
    relay("GET", `/u/${encodeURIComponent(username)}`),
    relay("GET", "/stories?limit=60"),
  ]);
  const own = profile.stories.slice(0, 40).map((story) => `- ${story.title}`).join("\n") || "(none yet)";
  const others = feed.stories.filter((story) => story.author !== profile.username).slice(0, 40)
    .map((story) => `- ${story.title}`).join("\n") || "(none yet)";

  const system = `You write stories for Kissa, a calm, text-only Android app where people swipe through news, Reddit stories and stories written for Kissa. You are writing as u/${username}, one of Kissa's house writers. Readers see a Kissa badge on house writers, so they know these are Kissa's own stories; write them to be good to read, not to pass as a real confession.

Who u/${username} is and how they write:
${voice.voice}

${voice.topics ? `What they tend to write about: ${voice.topics}\n\n` : ""}Write in ${voice.language === "es" ? "Spanish, the way people in Mazatlán, Sinaloa actually talk and write online" : "English"}. Aim for ${LENGTHS[voice.length]}.

What makes a Kissa story good:
- It reads like a person telling it: first person, specific, a little messy. Concrete details (a street, a brand, what someone said word for word) over summaries of feelings.
- Not every story needs a lesson, a twist or a neat ending. Leave some unresolved.
- Vary the shape: some start in the middle, some are a list of small moments, some are one conversation.
- Avoid the usual AI tells: names like Sarah, Emily, Jake or Mark; "little did I know"; "I'll never forget"; ending on a reflective moral; em dashes everywhere; every paragraph the same length.
- No real, identifiable private people, and nothing cruel for its own sake.`;

  const prompt = `${idea ? `Story idea from the editor: ${idea}\n\n` : "Pick the premise yourself.\n\n"}Titles u/${username} has already posted (don't retell these):
${own}

Recent titles by other Kissa writers (don't overlap these either):
${others}

Write one new story.`;

  claude ??= new Anthropic();
  const response = await claude.beta.messages.create({
    model: MODEL,
    max_tokens: 16000,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    output_config: { effort: "medium", format: { type: "json_schema", schema: STORY_SCHEMA } },
    system,
    messages: [{ role: "user", content: prompt }],
  });
  if (response.stop_reason === "refusal") {
    throw new HttpError(422, `Claude declined this one${response.stop_details?.explanation ? `: ${response.stop_details.explanation}` : "."} Try another idea.`);
  }
  if (response.stop_reason === "max_tokens") throw new HttpError(502, "The story ran too long and was cut off. Try again.");
  const text = response.content.filter((block) => block.type === "text").map((block) => block.text).join("");
  const story = JSON.parse(text);
  return { title: story.title.trim(), body: story.body.trim(), model: response.model, usage: response.usage };
}

// --- HTTP ---

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const ROUTES = [
  ["GET", /^\/api\/writers$/, async () => ({ writers: await listWriters() })],
  ["POST", /^\/api\/writers$/, async (_, body) => createWriter(body)],
  ["PUT", /^\/api\/writers\/(\w+)$/, async ([username], body) => ({ voice: await saveVoice(username, body) })],
  ["GET", /^\/api\/writers\/(\w+)$/, async ([username]) => relay("GET", `/u/${username}`)],
  ["POST", /^\/api\/writers\/(\w+)\/draft$/, async ([username], body) => draftStory(username, String(body.idea ?? "").trim())],
  ["POST", /^\/api\/stories$/, async (_, body) => relay("POST", "/api/stories", body, { admin: true })],
  ["DELETE", /^\/api\/stories\/(\w+)$/, async ([id]) => relay("DELETE", `/api/stories/${id}`, null, { admin: true })],
  ["GET", /^\/api\/stories$/, async () => relay("GET", "/stories?limit=100")],
  ["GET", /^\/api\/stories\/(\w+)\/comments$/, async ([id]) => relay("GET", `/stories/${id.replace(/^k_/, "")}/comments`)],
];

async function readBody(request) {
  let text = "";
  for await (const chunk of request) text += chunk;
  return text ? JSON.parse(text) : {};
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://localhost");
  const send = (status, data, type = "application/json") => {
    response.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store" });
    response.end(type === "application/json" ? JSON.stringify(data) : data);
  };
  try {
    if (request.method === "GET" && url.pathname === "/") {
      return send(200, await readFile(path.join(here, "index.html"), "utf8"), "text/html; charset=utf-8");
    }
    for (const [method, pattern, handler] of ROUTES) {
      const match = url.pathname.match(pattern);
      if (match && request.method === method) return send(200, await handler(match.slice(1), await readBody(request)));
    }
    send(404, { error: "Not found." });
  } catch (error) {
    if (error instanceof Anthropic.AuthenticationError) return send(500, { error: "ANTHROPIC_API_KEY is missing or wrong in studio/.env." });
    if (error instanceof Anthropic.RateLimitError) return send(429, { error: "Claude is rate limited right now. Wait a minute and try again." });
    if (error instanceof Anthropic.APIError) return send(502, { error: `Claude: ${error.message}` });
    if (!(error instanceof HttpError)) console.error(request.method, url.pathname, error);
    send(error.status || 500, { error: error instanceof HttpError ? error.message : "Something went wrong; see the terminal." });
  }
});

// Only this Mac can open it: the admin key never leaves the machine.
server.listen(PORT, "127.0.0.1", () => {
  console.log(`Kissa Studio: http://localhost:${PORT}  (relay ${RELAY}, ${MODEL})`);
  if (!ADMIN_KEY) console.log("KISSA_ADMIN_KEY isn't set; copy .env.example to .env and fill it in.");
});
