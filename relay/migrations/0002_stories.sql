-- Kissa's own stories, written in the app by authors (users.is_author = 1; set by hand in D1).

ALTER TABLE users ADD COLUMN is_author INTEGER NOT NULL DEFAULT 0;

-- Liked cards can be Kissa stories too.
ALTER TABLE likes ADD COLUMN is_original INTEGER NOT NULL DEFAULT 0;

CREATE TABLE stories (
  id TEXT PRIMARY KEY,
  author_id INTEGER NOT NULL REFERENCES users(id),
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  published_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX stories_published ON stories(published_at);
