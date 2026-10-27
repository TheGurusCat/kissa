-- Kissa accounts in D1 (replaces the ACCOUNTS KV namespace). Applied with
--   npx wrangler d1 migrations apply kissa --remote
-- Times are Unix milliseconds. Tokens and codes are only ever stored as SHA-256 hashes.

CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  -- The app's updatedAt for the last uploaded snapshot; 0 until the first upload.
  synced_at INTEGER NOT NULL DEFAULT 0,
  -- JSON arrays/objects the app sends as-is.
  subreddits TEXT,
  news_feeds TEXT,
  settings TEXT,
  seen TEXT
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

-- One pending code per address; sent_at throttles resends.
CREATE TABLE sign_in_codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  sent_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

-- Liked cards; position keeps the app's order (0 = top of the Liked list).
CREATE TABLE likes (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  post_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  liked_at INTEGER NOT NULL,
  subreddit TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  author TEXT NOT NULL DEFAULT '',
  score INTEGER NOT NULL DEFAULT 0,
  num_comments INTEGER NOT NULL DEFAULT 0,
  permalink TEXT NOT NULL,
  is_nsfw INTEGER NOT NULL DEFAULT 0,
  is_news INTEGER NOT NULL DEFAULT 0,
  published_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, post_id)
);
CREATE INDEX likes_post ON likes(post_id);
CREATE INDEX likes_subreddit ON likes(subreddit);

-- Stories read and seconds spent per local day (YYYY-MM-DD).
CREATE TABLE daily_stats (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,
  stories INTEGER NOT NULL DEFAULT 0,
  seconds INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);
CREATE INDEX daily_stats_day ON daily_stats(day);
