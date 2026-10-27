// Admin API for Kissa's own scripts: create house accounts and post stories and comments as them. Every request
// needs the ADMIN_API_KEY secret in an X-Kissa-Key header. House accounts show a Kissa badge to readers and can't
// sign in; they exist only to post through this API.
//
//   GET    /api/users                                      {users: [{username, joinedAt, stories, comments}]}
//   POST   /api/users     {username}                       creates a house account; returns {username}
//   POST   /api/stories   {username, title, body}          posts a story; returns {story}
//   POST   /api/comments  {username, storyId, body}        comments on a story (card ID or bare ID); returns {comment}
//   DELETE /api/stories/<id>, /api/comments/<id>           removes one (any author)

import { HttpError, RESERVED_USERNAMES, USERNAME_PATTERN, json, readJson } from "./account.js";
import { CARD_SELECT, MAX_COMMENT, card, insertComment, newId, removeComment, removeStory, validStory } from "./stories.js";

const ID = "([a-z0-9]{10})";

export async function handleApi(request, env, url) {
  if (!url.pathname.startsWith("/api/")) return null;
  try {
    if (!(await keyMatches(request.headers.get("X-Kissa-Key"), env.ADMIN_API_KEY))) {
      throw new HttpError(401, "Missing or wrong X-Kissa-Key.");
    }
    const route = `${request.method} ${url.pathname}`;
    let match;
    if (route === "GET /api/users") return await listHouseUsers(env);
    if (route === "POST /api/users") return await createHouseUser(request, env);
    if (route === "POST /api/stories") return await postStory(request, env, url);
    if (route === "POST /api/comments") return await postComment(request, env);
    if ((match = route.match(new RegExp(`^DELETE /api/stories/(?:k_)?${ID}$`)))) {
      await removeStory(env, match[1]);
      return json({ ok: true }, 200);
    }
    if ((match = route.match(new RegExp(`^DELETE /api/comments/${ID}$`)))) {
      await removeComment(env, match[1]);
      return json({ ok: true }, 200);
    }
    throw new HttpError(404, "Unknown API route.");
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error(request.method, url.pathname, error);
    return json({ error: "Something went wrong." }, 500);
  }
}

async function listHouseUsers(env) {
  const { results } = await env.DB.prepare(
    `SELECT u.username, u.created_at,
       (SELECT count(*) FROM stories s WHERE s.author_id = u.id) AS stories,
       (SELECT count(*) FROM comments c WHERE c.author_id = u.id) AS comments
     FROM users u WHERE u.is_house = 1 ORDER BY u.username_key`,
  ).all();
  return json({
    users: results.map((row) => ({ username: row.username, joinedAt: row.created_at, stories: row.stories, comments: row.comments })),
  }, 200);
}

async function createHouseUser(request, env) {
  const username = String((await readJson(request)).username ?? "").trim();
  if (!USERNAME_PATTERN.test(username)) throw new HttpError(400, "Use 3 to 20 letters, numbers or underscores.");
  const key = username.toLowerCase();
  if (RESERVED_USERNAMES.has(key)) throw new HttpError(409, "That username is reserved.");
  if (await env.DB.prepare("SELECT 1 FROM users WHERE username_key = ?").bind(key).first()) {
    throw new HttpError(409, "That username is taken.");
  }
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO users (email, created_at, last_seen_at, username, username_key, is_house) VALUES (?, ?, ?, ?, ?, 1)`,
  ).bind(`house+${key}@kissa.invalid`, now, now, username, key).run();
  return json({ username }, 200);
}

async function postStory(request, env, url) {
  const input = await readJson(request);
  const author = await houseUser(env, input.username);
  const { title, body } = validStory(input);
  const id = newId();
  const now = Date.now();
  await env.DB.prepare("INSERT INTO stories (id, author_id, title, body, published_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(id, author.id, title, body, now, now).run();
  return json({ story: card(await env.DB.prepare(`${CARD_SELECT} WHERE s.id = ?`).bind(id).first(), url.origin) }, 200);
}

async function postComment(request, env) {
  const input = await readJson(request);
  const author = await houseUser(env, input.username);
  const storyId = String(input.storyId ?? "").replace(/^k_/, "");
  const body = String(input.body ?? "").trim();
  if (!body) throw new HttpError(400, "The comment is empty.");
  if (body.length > MAX_COMMENT) throw new HttpError(400, `Keep comments under ${MAX_COMMENT} characters.`);
  const story = await env.DB.prepare("SELECT author_id FROM stories WHERE id = ?").bind(storyId).first();
  if (!story) throw new HttpError(404, "No story with that ID.");
  return json({ comment: await insertComment(env, storyId, story.author_id, author, body) }, 200);
}

/** Only house accounts can be posted as, so the API can never speak for a real reader. */
async function houseUser(env, username) {
  const user = await env.DB.prepare("SELECT id, username, is_house FROM users WHERE username_key = ?")
    .bind(String(username ?? "").toLowerCase()).first();
  if (!user) throw new HttpError(404, "No house account with that username. Create it with POST /api/users.");
  if (!user.is_house) throw new HttpError(403, "That's a reader's account; the API only posts as house accounts.");
  return user;
}

/** Compares in constant time, so the key can't be guessed one character at a time. */
async function keyMatches(given, expected) {
  if (!given || !expected) return false;
  const encode = (text) => crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  const [a, b] = await Promise.all([encode(given), encode(expected)]);
  return crypto.subtle.timingSafeEqual(a, b);
}
