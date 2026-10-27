// Kissa's own stories (the Stories tab). Anyone can read them; only authors (users.is_author) can write.
//
//   GET    /stories?before=&limit=   newest first, published before `before` (ms); {stories: [card]}
//   GET    /s/<id>                   the story as a web page, for share links and the Liked list
//   POST   /stories      {title, body}   author: publishes a story; returns {story}
//   PUT    /stories/<id> {title, body}   author: edits one of their stories; returns {story}
//   DELETE /stories/<id>                 author: deletes one of their stories
//
// Cards use the app's post shape (see RedditPost.kt) with isOriginal set; their IDs are "k_" + the story ID.

import { HttpError, json, readJson, requireSession } from "./account.js";

const MAX_TITLE = 300;
const MAX_BODY = 40_000;
const PAGE_SIZE = 50;
const STORY_PATH = /^\/stories\/([a-z0-9]{10})$/;
const PAGE_PATH = /^\/s\/([a-z0-9]{10})$/;

export async function handleStories(request, env, url) {
  const { pathname } = url;
  const method = request.method;
  try {
    if (pathname === "/stories" && method === "GET") return await listStories(env, url);
    if (pathname === "/stories" && method === "POST") return await createStory(request, env, url);
    const page = pathname.match(PAGE_PATH);
    if (page && method === "GET") return await storyPage(env, page[1]);
    const one = pathname.match(STORY_PATH);
    if (one && method === "PUT") return await editStory(request, env, url, one[1]);
    if (one && method === "DELETE") return await deleteStory(request, env, one[1]);
    return null;
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error(method, pathname, error);
    return json({ error: "Something went wrong. Try again in a moment." }, 500);
  }
}

async function listStories(env, url) {
  const before = Number(url.searchParams.get("before")) || Number.MAX_SAFE_INTEGER;
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit")) || PAGE_SIZE, 1), 100);
  const { results } = await env.DB.prepare(
    "SELECT id, title, body, published_at FROM stories WHERE published_at < ? ORDER BY published_at DESC LIMIT ?",
  ).bind(before, limit).all();
  return json({ stories: results.map((row) => card(row, url.origin)) }, 200);
}

async function createStory(request, env, url) {
  const authorId = await requireAuthor(request, env);
  const { title, body } = validStory(await readJson(request));
  const now = Date.now();
  const id = newId();
  await env.DB.prepare("INSERT INTO stories (id, author_id, title, body, published_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(id, authorId, title, body, now, now).run();
  return json({ story: card({ id, title, body, published_at: now }, url.origin) }, 200);
}

async function editStory(request, env, url, id) {
  const authorId = await requireAuthor(request, env);
  const { title, body } = validStory(await readJson(request));
  const row = await env.DB.prepare(
    "UPDATE stories SET title = ?, body = ?, updated_at = ? WHERE id = ? AND author_id = ? RETURNING published_at",
  ).bind(title, body, Date.now(), id, authorId).first();
  if (!row) throw new HttpError(404, "That story is gone.");
  return json({ story: card({ id, title, body, published_at: row.published_at }, url.origin) }, 200);
}

async function deleteStory(request, env, id) {
  const authorId = await requireAuthor(request, env);
  await env.DB.prepare("DELETE FROM stories WHERE id = ? AND author_id = ?").bind(id, authorId).run();
  return json({ ok: true }, 200);
}

async function storyPage(env, id) {
  const row = await env.DB.prepare("SELECT title, body, published_at FROM stories WHERE id = ?").bind(id).first();
  const html = row ? pageHtml(row) : pageHtml({ title: "Story not found", body: "This story was removed.", published_at: 0 });
  return new Response(html, {
    status: row ? 200 : 404,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "public, max-age=300" },
  });
}

async function requireAuthor(request, env) {
  const userId = await requireSession(request, env);
  const user = await env.DB.prepare("SELECT is_author FROM users WHERE id = ?").bind(userId).first();
  if (!user?.is_author) throw new HttpError(403, "Only Kissa authors can post stories.");
  return userId;
}

function validStory(input) {
  const title = String(input.title ?? "").trim();
  const body = String(input.body ?? "").trim();
  if (!title) throw new HttpError(400, "Give the story a title.");
  if (!body) throw new HttpError(400, "The story is empty.");
  if (title.length > MAX_TITLE) throw new HttpError(400, `Keep the title under ${MAX_TITLE} characters.`);
  if (body.length > MAX_BODY) throw new HttpError(400, `Keep the story under ${MAX_BODY} characters.`);
  return { title, body };
}

function card(row, origin) {
  return {
    id: `k_${row.id}`,
    subreddit: "Kissa",
    title: row.title,
    body: row.body,
    author: "",
    score: 0,
    numComments: 0,
    permalink: `${origin}/s/${row.id}`,
    isNsfw: false,
    isNews: false,
    isOriginal: true,
    publishedAt: row.published_at,
  };
}

function newId() {
  const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
  return [...crypto.getRandomValues(new Uint8Array(10))].map((b) => alphabet[b % alphabet.length]).join("");
}

function pageHtml({ title, body, published_at }) {
  const date = published_at
    ? new Date(published_at).toLocaleDateString("en", { year: "numeric", month: "long", day: "numeric" })
    : "";
  const paragraphs = body.split(/\n\s*\n/).map((p) => `<p>${escape(p).replace(/\n/g, "<br>")}</p>`).join("\n");
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)} · Kissa</title>
<style>
  :root { --bg: #faf8f5; --ink: #1d1b19; --muted: #6b655e; --accent: #b4532a; color-scheme: light dark; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121110; --ink: #ece7e1; --muted: #9a938b; --accent: #e08a5f; } }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 19px/1.65 Georgia, "Times New Roman", serif; }
  main { max-width: 640px; margin: 0 auto; padding: 40px 16px 64px; }
  .brand { font: 600 14px/1 system-ui, sans-serif; letter-spacing: .08em; text-transform: uppercase; color: var(--accent); }
  h1 { font-size: 30px; line-height: 1.25; margin: 16px 0 6px; }
  .date { font: 14px system-ui, sans-serif; color: var(--muted); margin-bottom: 28px; }
</style></head>
<body><main><div class="brand">Kissa</div><h1>${escape(title)}</h1><div class="date">${date}</div>
${paragraphs}
</main></body></html>`;
}

function escape(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
