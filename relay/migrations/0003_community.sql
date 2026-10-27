-- Open community: anyone signed in with a username can post, vote, comment and report.
-- users.is_author (0002) is no longer used; is_admin marks moderators (set by hand in D1).

ALTER TABLE users ADD COLUMN username TEXT;
-- Lowercased username, so "Andre" and "andre" can't both exist.
ALTER TABLE users ADD COLUMN username_key TEXT;
ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0;
-- Banned accounts can still sign in and sync, but not post, vote, comment or report.
ALTER TABLE users ADD COLUMN is_banned INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX users_username ON users(username_key);

CREATE INDEX stories_author ON stories(author_id);

-- An upvote per reader per story (a right swipe on a Kissa story).
CREATE TABLE votes (
  story_id TEXT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (story_id, user_id)
);

CREATE TABLE comments (
  id TEXT PRIMARY KEY,
  story_id TEXT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  author_id INTEGER NOT NULL REFERENCES users(id),
  body TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX comments_story ON comments(story_id, created_at);
CREATE INDEX comments_author ON comments(author_id, created_at);

-- What readers flagged; resolved_at is set when an admin removes the content or dismisses the report.
CREATE TABLE reports (
  id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('story', 'comment')),
  target_id TEXT NOT NULL,
  reporter_id INTEGER NOT NULL REFERENCES users(id),
  reason TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  resolved_at INTEGER
);
CREATE INDEX reports_open ON reports(resolved_at, created_at);
CREATE UNIQUE INDEX reports_once ON reports(kind, target_id, reporter_id);
