// Kissa accounts: sign in with Google or a 6-digit code sent by email, pick a username to post and comment,
// and sync liked cards, sources, settings and daily reading stats across phones.
//
//   POST /auth/code    {email}          emails a code (one per minute per address, valid 10 minutes)
//   POST /auth/verify  {email, code}    returns {token, email, profile}; 5 wrong tries and the code is void
//   POST /auth/google  {idToken}        signs in with a Google ID token; same reply as /auth/verify
//   POST /auth/signout                  ends this session
//   GET  /me                            returns {email, profile}
//   PUT  /me           {username}       picks or changes the username; returns {email, profile}
//   GET  /sync                          returns {data, updatedAt, profile} (data is null before the first upload)
//   PUT  /sync         {data, updatedAt} replaces the account's data with this snapshot
//
// profile is {username, isAdmin, isBanned}; username is null until one is picked.
//
// The app still sends and receives one JSON snapshot; here it is split into D1 tables (DB binding, schema in
// ../migrations): users, sessions, sign_in_codes, likes and daily_stats. Tokens and codes are only ever stored hashed.

import { verifiedGoogleEmail } from "./google.js";

const CODE_TTL_MS = 10 * 60 * 1000;
const RESEND_WAIT_MS = 60 * 1000;
const MAX_CODE_ATTEMPTS = 5;
const SESSION_TTL_MS = 365 * 24 * 3600 * 1000;
const MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
const MAX_LIKES = 5000;
const EMAIL_PATTERN = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,20}$/;
const RESERVED_USERNAMES = new Set(["admin", "administrator", "kissa", "mod", "moderator", "support", "deleted", "system"]);


export async function handleAccount(request, env, url) {
  const route = `${request.method} ${url.pathname}`;
  try {
    switch (route) {
      case "POST /auth/code": return await sendCode(request, env);
      case "POST /auth/verify": return await verifyCode(request, env);
      case "POST /auth/google": return await googleSignIn(request, env);
      case "GET /me": return await getMe(request, env);
      case "PUT /me": return await setUsername(request, env);
      case "POST /auth/signout": return await signOut(request, env);
      case "GET /sync": return await getSnapshot(request, env);
      case "PUT /sync": return await putSnapshot(request, env);
      default: return null;
    }
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error(route, error);
    return json({ error: "Something went wrong. Try again in a moment." }, 500);
  }
}

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function sendCode(request, env) {
  const email = normalizeEmail((await readJson(request)).email);
  const now = Date.now();
  const pending = await env.DB.prepare("SELECT sent_at FROM sign_in_codes WHERE email = ?").bind(email).first();
  if (pending && now - pending.sent_at < RESEND_WAIT_MS) {
    throw new HttpError(429, "A code was just sent. Wait a minute before asking for another.");
  }
  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
  await env.DB.prepare(
    `INSERT INTO sign_in_codes (email, code_hash, attempts, sent_at, expires_at) VALUES (?, ?, 0, ?, ?)
     ON CONFLICT(email) DO UPDATE SET code_hash = excluded.code_hash, attempts = 0,
       sent_at = excluded.sent_at, expires_at = excluded.expires_at`,
  ).bind(email, await sha256(`${email}:${code}`), now, now + CODE_TTL_MS).run();
  try {
    await env.EMAIL.send({
      from: env.SENDER,
      to: email,
      subject: `${code} is your Kissa code`,
      text: `Your Kissa sign-in code is ${code}\n\nIt works for 10 minutes. If you didn't ask for it, ignore this email.`,
      html: `<p>Your Kissa sign-in code is</p><p style="font-size:28px;font-weight:bold;letter-spacing:4px">${code}</p>` +
        `<p>It works for 10 minutes. If you didn't ask for it, ignore this email.</p>`,
    });
  } catch (error) {
    console.error("Email sending failed:", error.code, error.message);
    await env.DB.prepare("DELETE FROM sign_in_codes WHERE email = ?").bind(email).run();
    throw new HttpError(502, "Couldn't send the email. Check the address and try again.");
  }
  return json({ ok: true }, 200);
}

async function verifyCode(request, env) {
  const body = await readJson(request);
  const email = normalizeEmail(body.email);
  const code = String(body.code ?? "").trim();
  const now = Date.now();
  const stored = await env.DB.prepare("SELECT code_hash, attempts, expires_at FROM sign_in_codes WHERE email = ?")
    .bind(email).first();
  if (!stored || stored.expires_at < now) throw new HttpError(400, "That code has expired. Ask for a new one.");
  if (stored.code_hash !== (await sha256(`${email}:${code}`))) {
    if (stored.attempts + 1 >= MAX_CODE_ATTEMPTS) {
      await env.DB.prepare("DELETE FROM sign_in_codes WHERE email = ?").bind(email).run();
      throw new HttpError(400, "Too many wrong tries. Ask for a new code.");
    }
    await env.DB.prepare("UPDATE sign_in_codes SET attempts = attempts + 1 WHERE email = ?").bind(email).run();
    throw new HttpError(400, "That code isn't right.");
  }
  await env.DB.prepare("DELETE FROM sign_in_codes WHERE email = ?").bind(email).run();
  return await startSession(env, email);
}

async function googleSignIn(request, env) {
  if (!env.GOOGLE_CLIENT_ID) throw new HttpError(503, "Google sign-in isn't available yet. Use your email.");
  const email = await verifiedGoogleEmail((await readJson(request)).idToken, env);
  if (!email) throw new HttpError(401, "Google sign-in didn't work. Try again.");
  return await startSession(env, normalizeEmail(email));
}

/** Signs [email] in, creating its account the first time; the same for email codes and Google. */
async function startSession(env, email) {
  const now = Date.now();
  const user = await env.DB.prepare(
    `INSERT INTO users (email, created_at, last_seen_at) VALUES (?, ?, ?)
     ON CONFLICT(email) DO UPDATE SET last_seen_at = excluded.last_seen_at RETURNING *`,
  ).bind(email, now, now).first();
  const token = hex(crypto.getRandomValues(new Uint8Array(32)));
  await env.DB.batch([
    env.DB.prepare("INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
      .bind(await sha256(token), user.id, now, now + SESSION_TTL_MS),
    // Expired sessions of anyone are cleared now and then, on sign-in.
    env.DB.prepare("DELETE FROM sessions WHERE expires_at < ?").bind(now),
  ]);
  return json({ token, email, profile: profile(user) }, 200);
}

async function getMe(request, env) {
  const user = await requireUser(request, env);
  return json({ email: user.email, profile: profile(user) }, 200);
}

async function setUsername(request, env) {
  const user = await requireUser(request, env);
  const username = String((await readJson(request)).username ?? "").trim().replace(/^u\//, "");
  if (!USERNAME_PATTERN.test(username)) {
    throw new HttpError(400, "Use 3 to 20 letters, numbers or underscores.");
  }
  const key = username.toLowerCase();
  if (RESERVED_USERNAMES.has(key)) throw new HttpError(409, "That username is taken.");
  const taken = await env.DB.prepare("SELECT id FROM users WHERE username_key = ? AND id != ?").bind(key, user.id).first();
  if (taken) throw new HttpError(409, "That username is taken.");
  const updated = await env.DB.prepare("UPDATE users SET username = ?, username_key = ? WHERE id = ? RETURNING *")
    .bind(username, key, user.id).first();
  return json({ email: updated.email, profile: profile(updated) }, 200);
}

/** What the app needs to know about an account besides its synced data. */
export function profile(user) {
  return { username: user.username ?? null, isAdmin: !!user.is_admin, isBanned: !!user.is_banned };
}

async function signOut(request, env) {
  const token = bearer(request);
  if (token) await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256(token)).run();
  return json({ ok: true }, 200);
}

async function getSnapshot(request, env) {
  const user = await requireUser(request, env);
  const userId = user.id;
  if (!user.synced_at) return json({ data: null, updatedAt: 0, profile: profile(user) }, 200);
  const [likes, stats] = await env.DB.batch([
    env.DB.prepare("SELECT * FROM likes WHERE user_id = ? ORDER BY position").bind(userId),
    env.DB.prepare("SELECT day, stories, seconds FROM daily_stats WHERE user_id = ?").bind(userId),
  ]);
  const data = {
    liked: likes.results.map((row) => ({
      id: row.post_id,
      subreddit: row.subreddit,
      title: row.title,
      body: row.body,
      author: row.author,
      score: row.score,
      numComments: row.num_comments,
      permalink: row.permalink,
      isNsfw: !!row.is_nsfw,
      isNews: !!row.is_news,
      isOriginal: !!row.is_original,
      publishedAt: row.published_at,
    })),
    stats: Object.fromEntries(stats.results.map((row) => [row.day, { stories: row.stories, seconds: row.seconds }])),
  };
  // Parts this account never uploaded stay out, so the phone keeps its own.
  for (const [key, column] of [["subreddits", "subreddits"], ["newsFeeds", "news_feeds"], ["settings", "settings"], ["seen", "seen"]]) {
    if (user[column] != null) data[key] = JSON.parse(user[column]);
  }
  return json({ data, updatedAt: user.synced_at, profile: profile(user) }, 200);
}

async function putSnapshot(request, env) {
  const userId = await requireSession(request, env);
  const text = await request.text();
  if (text.length > MAX_SNAPSHOT_BYTES) throw new HttpError(413, "Too much to sync.");
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new HttpError(400, "Expected JSON.");
  }
  const data = body.data;
  if (typeof data !== "object" || data === null || typeof body.updatedAt !== "number") {
    throw new HttpError(400, "Expected {data, updatedAt}.");
  }
  await env.DB.batch(snapshotStatements(env.DB, userId, data, body.updatedAt, Date.now()));
  return json({ ok: true, updatedAt: body.updatedAt }, 200);
}

/** The statements that make the account's rows match one uploaded snapshot. */
function snapshotStatements(db, userId, data, updatedAt, now) {
  const liked = Array.isArray(data.liked) ? data.liked.filter(isPost).slice(0, MAX_LIKES) : null;
  const statements = [
    db.prepare(
      // A part missing from the snapshot keeps what was stored.
      `UPDATE users SET synced_at = ?, last_seen_at = ?, subreddits = COALESCE(?, subreddits),
         news_feeds = COALESCE(?, news_feeds), settings = COALESCE(?, settings), seen = COALESCE(?, seen)
       WHERE id = ?`,
    ).bind(
      updatedAt,
      now,
      jsonOrNull(data.subreddits, Array.isArray),
      jsonOrNull(data.newsFeeds, Array.isArray),
      jsonOrNull(data.settings, (v) => typeof v === "object" && v !== null && !Array.isArray(v)),
      jsonOrNull(data.seen, Array.isArray),
      userId,
    ),
  ];
  if (liked) {
    // Unliked cards go; the rest keep the time they were first liked.
    statements.push(
      db.prepare("DELETE FROM likes WHERE user_id = ? AND post_id NOT IN (SELECT value FROM json_each(?))")
        .bind(userId, JSON.stringify(liked.map((post) => post.id))),
    );
    liked.forEach((post, position) => statements.push(
      db.prepare(
        `INSERT INTO likes (user_id, post_id, position, liked_at, subreddit, title, body, author, score, num_comments,
           permalink, is_nsfw, is_news, is_original, published_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id, post_id) DO UPDATE SET position = excluded.position, title = excluded.title,
           body = excluded.body, score = excluded.score, num_comments = excluded.num_comments`,
      ).bind(
        userId, post.id, position, now, post.subreddit, post.title, String(post.body ?? ""), String(post.author ?? ""),
        int(post.score), int(post.numComments), post.permalink, post.isNsfw ? 1 : 0, post.isNews ? 1 : 0,
        post.isOriginal ? 1 : 0, int(post.publishedAt),
      ),
    ));
  }
  if (typeof data.stats === "object" && data.stats !== null) {
    for (const [day, entry] of Object.entries(data.stats)) {
      if (!DAY_PATTERN.test(day) || typeof entry !== "object" || entry === null) continue;
      statements.push(
        db.prepare(
          `INSERT INTO daily_stats (user_id, day, stories, seconds) VALUES (?, ?, ?, ?)
           ON CONFLICT(user_id, day) DO UPDATE SET stories = excluded.stories, seconds = excluded.seconds`,
        ).bind(userId, day, Math.max(0, int(entry.stories)), Math.max(0, int(entry.seconds))),
      );
    }
  }
  return statements;
}

function isPost(post) {
  return typeof post === "object" && post !== null &&
    ["id", "subreddit", "title", "permalink"].every((key) => typeof post[key] === "string");
}

function jsonOrNull(value, valid) {
  return valid(value) ? JSON.stringify(value) : null;
}

function int(value) {
  const number = Math.trunc(Number(value));
  return Number.isFinite(number) ? number : 0;
}

export async function requireSession(request, env) {
  return (await requireUser(request, env)).id;
}

/** The signed-in account's users row; throws 401 if signed out. */
export async function requireUser(request, env) {
  const user = await currentUser(request, env);
  if (!user) throw new HttpError(401, "Signed out. Sign in again.");
  return user;
}

/** The signed-in account's users row, or null when the request has no valid session. */
export async function currentUser(request, env) {
  const token = bearer(request);
  if (!token) return null;
  return await env.DB.prepare(
    "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at >= ?",
  ).bind(await sha256(token), Date.now()).first();
}

function bearer(request) {
  const header = request.headers.get("Authorization") ?? "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : null;
}

function normalizeEmail(value) {
  const email = String(value ?? "").trim().toLowerCase();
  if (!EMAIL_PATTERN.test(email)) throw new HttpError(400, "That doesn't look like an email address.");
  return email;
}

export async function readJson(request) {
  try {
    return await request.json();
  } catch {
    throw new HttpError(400, "Expected JSON.");
  }
}

async function sha256(text) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))));
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
