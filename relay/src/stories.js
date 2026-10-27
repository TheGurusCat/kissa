// Kissa's own stories (the Stories tab), written by its readers. Anyone can read; signed-in accounts with a
// username can post, upvote, comment and report; admins (users.is_admin) moderate.
//
//   GET    /stories?before=&limit=        newest first, published before `before` (ms); {stories: [card]}
//   GET    /s/<id>                        the story as a web page, for share links and the Liked list
//   POST   /stories             {title, body}  publishes a story; returns {story}
//   PUT    /stories/<id>        {title, body}  edits your own story; returns {story}
//   DELETE /stories/<id>                       deletes your own story (admins: any)
//   POST   /stories/<id>/vote                  upvotes; DELETE takes the vote back; both return {score}
//   GET    /stories/<id>/comments              {comments: [comment]}, oldest first
//   POST   /stories/<id>/comments  {body}      returns {comment}
//   DELETE /comments/<id>                      deletes your own comment (admins: any)
//   GET    /u/<username>                       {username, joinedAt, stories: [card]}
//   POST   /reports      {kind, id, reason}    kind is "story" or "comment"
//   GET    /admin/reports                      admins: open reports with what was reported
//   POST   /admin/reports/<id>/dismiss         admins: closes a report without removing anything
//   POST   /admin/users/<username>/ban {banned} admins: bans or unbans an account
//
// Cards use the app's post shape (see RedditPost.kt) with isOriginal set: IDs are "k_" + the story ID, author
// is the username, score the upvotes and numComments the comments.

import { HttpError, currentUser, json, readJson, requireUser } from "./account.js";

const MAX_TITLE = 300;
const MAX_BODY = 40_000;
const MAX_COMMENT = 5_000;
const MAX_REASON = 500;
const PAGE_SIZE = 50;
const DAY_MS = 24 * 3600 * 1000;
// Per account per day, to keep spam down.
const DAILY_STORIES = 10;
const DAILY_COMMENTS = 200;
const DAILY_REPORTS = 50;

const ID = "([a-z0-9]{10})";
const ROUTES = [
  ["GET", /^\/stories$/, listStories],
  ["POST", /^\/stories$/, createStory],
  ["GET", new RegExp(`^/s/${ID}$`), storyPage],
  ["PUT", new RegExp(`^/stories/${ID}$`), editStory],
  ["DELETE", new RegExp(`^/stories/${ID}$`), deleteStory],
  ["POST", new RegExp(`^/stories/${ID}/vote$`), (c, id) => vote(c, id, true)],
  ["DELETE", new RegExp(`^/stories/${ID}/vote$`), (c, id) => vote(c, id, false)],
  ["GET", new RegExp(`^/stories/${ID}/comments$`), listComments],
  ["POST", new RegExp(`^/stories/${ID}/comments$`), addComment],
  ["DELETE", new RegExp(`^/comments/${ID}$`), deleteComment],
  ["GET", /^\/u\/([A-Za-z0-9_]{3,20})$/, userProfile],
  ["POST", /^\/reports$/, report],
  ["GET", /^\/admin\/reports$/, openReports],
  ["POST", /^\/admin\/reports\/(\d+)\/dismiss$/, dismissReport],
  ["POST", /^\/admin\/users\/([A-Za-z0-9_]{3,20})\/ban$/, banUser],
];

// What a card needs besides the story row: the author's username, upvotes and comments.
const CARD_SELECT = `SELECT s.id, s.title, s.body, s.published_at, u.username AS author,
    (SELECT count(*) FROM votes v WHERE v.story_id = s.id) AS score,
    (SELECT count(*) FROM comments c WHERE c.story_id = s.id) AS comments
  FROM stories s JOIN users u ON u.id = s.author_id`;

export async function handleStories(request, env, url) {
  const route = ROUTES.find(([method, pattern]) => method === request.method && pattern.test(url.pathname));
  if (!route) return null;
  const [, pattern, handler] = route;
  try {
    return await handler({ request, env, url }, ...url.pathname.match(pattern).slice(1));
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error(request.method, url.pathname, error);
    return json({ error: "Something went wrong. Try again in a moment." }, 500);
  }
}

// --- Stories ---

async function listStories({ env, url }) {
  const before = Number(url.searchParams.get("before")) || Number.MAX_SAFE_INTEGER;
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit")) || PAGE_SIZE, 1), 100);
  const { results } = await env.DB.prepare(`${CARD_SELECT} WHERE s.published_at < ? ORDER BY s.published_at DESC LIMIT ?`)
    .bind(before, limit).all();
  return json({ stories: results.map((row) => card(row, url.origin)) }, 200);
}

async function createStory({ request, env, url }) {
  const user = await requireMember(request, env);
  const { title, body } = validStory(await readJson(request));
  await underDailyLimit(env, "stories", "author_id", "published_at", user.id, DAILY_STORIES, "stories");
  const now = Date.now();
  const id = newId();
  await env.DB.prepare("INSERT INTO stories (id, author_id, title, body, published_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(id, user.id, title, body, now, now).run();
  return json({ story: await storyCard(env, id, url.origin) }, 200);
}

async function editStory({ request, env, url }, id) {
  const user = await requireMember(request, env);
  const { title, body } = validStory(await readJson(request));
  const row = await env.DB.prepare("UPDATE stories SET title = ?, body = ?, updated_at = ? WHERE id = ? AND author_id = ? RETURNING id")
    .bind(title, body, Date.now(), id, user.id).first();
  if (!row) throw new HttpError(404, "That story is gone.");
  return json({ story: await storyCard(env, id, url.origin) }, 200);
}

async function deleteStory({ request, env }, id) {
  const user = await requireUser(request, env);
  const story = await env.DB.prepare("SELECT author_id FROM stories WHERE id = ?").bind(id).first();
  if (story && (story.author_id === user.id || user.is_admin)) {
    // Comments and votes go with it (ON DELETE CASCADE isn't enforced in D1 by default).
    await env.DB.batch([
      env.DB.prepare("DELETE FROM votes WHERE story_id = ?").bind(id),
      env.DB.prepare("UPDATE reports SET resolved_at = ? WHERE resolved_at IS NULL AND ((kind = 'story' AND target_id = ?) OR (kind = 'comment' AND target_id IN (SELECT id FROM comments WHERE story_id = ?)))")
        .bind(Date.now(), id, id),
      env.DB.prepare("DELETE FROM comments WHERE story_id = ?").bind(id),
      env.DB.prepare("DELETE FROM stories WHERE id = ?").bind(id),
    ]);
  } else if (story) {
    throw new HttpError(403, "You can only delete your own stories.");
  }
  return json({ ok: true }, 200);
}

async function vote({ request, env }, id, up) {
  const user = await requireMember(request, env, { needsUsername: false });
  if (!(await env.DB.prepare("SELECT 1 FROM stories WHERE id = ?").bind(id).first())) throw new HttpError(404, "That story is gone.");
  await (up
    ? env.DB.prepare("INSERT OR IGNORE INTO votes (story_id, user_id, created_at) VALUES (?, ?, ?)").bind(id, user.id, Date.now())
    : env.DB.prepare("DELETE FROM votes WHERE story_id = ? AND user_id = ?").bind(id, user.id)
  ).run();
  const { score } = await env.DB.prepare("SELECT count(*) AS score FROM votes WHERE story_id = ?").bind(id).first();
  return json({ score }, 200);
}

async function storyPage({ env }, id) {
  const row = await env.DB.prepare(`${CARD_SELECT} WHERE s.id = ?`).bind(id).first();
  const html = row ? pageHtml(row) : pageHtml({ title: "Story not found", body: "This story was removed.", published_at: 0 });
  return new Response(html, {
    status: row ? 200 : 404,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "public, max-age=300" },
  });
}

async function storyCard(env, id, origin) {
  return card(await env.DB.prepare(`${CARD_SELECT} WHERE s.id = ?`).bind(id).first(), origin);
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

// --- Comments ---

async function listComments({ env }, storyId) {
  const { results } = await env.DB.prepare(
    `SELECT c.id, c.body, c.created_at, u.username AS author FROM comments c JOIN users u ON u.id = c.author_id
     WHERE c.story_id = ? ORDER BY c.created_at`,
  ).bind(storyId).all();
  return json({ comments: results.map(comment) }, 200);
}

async function addComment({ request, env }, storyId) {
  const user = await requireMember(request, env);
  const body = String((await readJson(request)).body ?? "").trim();
  if (!body) throw new HttpError(400, "The comment is empty.");
  if (body.length > MAX_COMMENT) throw new HttpError(400, `Keep comments under ${MAX_COMMENT} characters.`);
  if (!(await env.DB.prepare("SELECT 1 FROM stories WHERE id = ?").bind(storyId).first())) throw new HttpError(404, "That story is gone.");
  await underDailyLimit(env, "comments", "author_id", "created_at", user.id, DAILY_COMMENTS, "comments");
  const row = { id: newId(), body, created_at: Date.now(), author: user.username };
  await env.DB.prepare("INSERT INTO comments (id, story_id, author_id, body, created_at) VALUES (?, ?, ?, ?, ?)")
    .bind(row.id, storyId, user.id, body, row.created_at).run();
  return json({ comment: comment(row) }, 200);
}

async function deleteComment({ request, env }, id) {
  const user = await requireUser(request, env);
  const row = await env.DB.prepare("SELECT author_id FROM comments WHERE id = ?").bind(id).first();
  if (row && (row.author_id === user.id || user.is_admin)) {
    await env.DB.batch([
      env.DB.prepare("UPDATE reports SET resolved_at = ? WHERE kind = 'comment' AND target_id = ? AND resolved_at IS NULL").bind(Date.now(), id),
      env.DB.prepare("DELETE FROM comments WHERE id = ?").bind(id),
    ]);
  } else if (row) {
    throw new HttpError(403, "You can only delete your own comments.");
  }
  return json({ ok: true }, 200);
}

function comment(row) {
  return { id: row.id, author: row.author, body: row.body, createdAt: row.created_at };
}

// --- Profiles ---

async function userProfile({ env, url }, username) {
  const user = await env.DB.prepare("SELECT id, username, created_at FROM users WHERE username_key = ?")
    .bind(username.toLowerCase()).first();
  if (!user) throw new HttpError(404, "There's no one called u/" + username + ".");
  const { results } = await env.DB.prepare(`${CARD_SELECT} WHERE s.author_id = ? ORDER BY s.published_at DESC LIMIT 200`)
    .bind(user.id).all();
  return json({ username: user.username, joinedAt: user.created_at, stories: results.map((row) => card(row, url.origin)) }, 200);
}

// --- Reports and moderation ---

async function report({ request, env }) {
  const user = await requireMember(request, env, { needsUsername: false });
  const input = await readJson(request);
  const kind = input.kind === "comment" ? "comment" : input.kind === "story" ? "story" : null;
  const id = String(input.id ?? "").replace(/^k_/, "");
  const reason = String(input.reason ?? "").trim().slice(0, MAX_REASON) || "No reason given";
  if (!kind || !/^[a-z0-9]{10}$/.test(id)) throw new HttpError(400, "Expected {kind, id, reason}.");
  const table = kind === "story" ? "stories" : "comments";
  if (!(await env.DB.prepare(`SELECT 1 FROM ${table} WHERE id = ?`).bind(id).first())) throw new HttpError(404, "That's already gone.");
  await underDailyLimit(env, "reports", "reporter_id", "created_at", user.id, DAILY_REPORTS, "reports");
  await env.DB.prepare(
    "INSERT OR IGNORE INTO reports (kind, target_id, reporter_id, reason, created_at) VALUES (?, ?, ?, ?, ?)",
  ).bind(kind, id, user.id, reason, Date.now()).run();
  return json({ ok: true }, 200);
}

async function openReports({ request, env, url }) {
  await requireAdmin(request, env);
  const { results } = await env.DB.prepare(
    `SELECT r.id, r.kind, r.target_id, r.reason, r.created_at, reporter.username AS reporter,
       COALESCE(s.title, c.body) AS content, COALESCE(sa.username, ca.username) AS author,
       COALESCE(sa.is_banned, ca.is_banned) AS author_banned, c.story_id
     FROM reports r
     JOIN users reporter ON reporter.id = r.reporter_id
     LEFT JOIN stories s ON r.kind = 'story' AND s.id = r.target_id
     LEFT JOIN users sa ON sa.id = s.author_id
     LEFT JOIN comments c ON r.kind = 'comment' AND c.id = r.target_id
     LEFT JOIN users ca ON ca.id = c.author_id
     WHERE r.resolved_at IS NULL ORDER BY r.created_at LIMIT 200`,
  ).all();
  return json({
    reports: results.map((row) => ({
      id: row.id,
      kind: row.kind,
      targetId: row.target_id,
      storyId: row.story_id ?? row.target_id,
      reason: row.reason,
      reporter: row.reporter,
      content: row.content,
      author: row.author,
      authorBanned: !!row.author_banned,
      createdAt: row.created_at,
      link: `${url.origin}/s/${row.story_id ?? row.target_id}`,
    })),
  }, 200);
}

async function dismissReport({ request, env }, id) {
  await requireAdmin(request, env);
  await env.DB.prepare("UPDATE reports SET resolved_at = ? WHERE id = ?").bind(Date.now(), Number(id)).run();
  return json({ ok: true }, 200);
}

async function banUser({ request, env }, username) {
  const admin = await requireAdmin(request, env);
  const banned = (await readJson(request)).banned !== false;
  const row = await env.DB.prepare("UPDATE users SET is_banned = ? WHERE username_key = ? AND id != ? RETURNING id")
    .bind(banned ? 1 : 0, username.toLowerCase(), admin.id).first();
  if (!row) throw new HttpError(404, "There's no one called u/" + username + ".");
  return json({ ok: true, banned }, 200);
}

// --- Helpers ---

/** A signed-in, unbanned account; with [needsUsername] (the default) it must also have picked a username. */
async function requireMember(request, env, { needsUsername = true } = {}) {
  const user = await requireUser(request, env);
  if (user.is_banned) throw new HttpError(403, "This account can't post or comment.");
  if (needsUsername && !user.username) throw new HttpError(403, "Pick a username first (menu → Account).");
  return user;
}

async function requireAdmin(request, env) {
  const user = await currentUser(request, env);
  if (!user?.is_admin) throw new HttpError(403, "Admins only.");
  return user;
}

async function underDailyLimit(env, table, userColumn, timeColumn, userId, limit, noun) {
  const { n } = await env.DB.prepare(`SELECT count(*) AS n FROM ${table} WHERE ${userColumn} = ? AND ${timeColumn} > ?`)
    .bind(userId, Date.now() - DAY_MS).first();
  if (n >= limit) throw new HttpError(429, `That's the limit of ${limit} ${noun} a day. Try again tomorrow.`);
}

function card(row, origin) {
  return {
    id: `k_${row.id}`,
    subreddit: "Kissa",
    title: row.title,
    body: row.body,
    author: row.author ?? "",
    score: row.score ?? 0,
    numComments: row.comments ?? 0,
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

function pageHtml({ title, body, published_at, author }) {
  const date = published_at
    ? new Date(published_at).toLocaleDateString("en", { year: "numeric", month: "long", day: "numeric" })
    : "";
  const byline = [author ? `by u/${escape(author)}` : "", date].filter(Boolean).join(" · ");
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
  .byline { font: 14px system-ui, sans-serif; color: var(--muted); margin-bottom: 28px; }
</style></head>
<body><main><div class="brand">Kissa</div><h1>${escape(title)}</h1><div class="byline">${byline}</div>
${paragraphs}
</main></body></html>`;
}

function escape(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
