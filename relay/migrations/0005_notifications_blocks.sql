-- What a writer hears about: someone liked their story or commented on it. One like notification per reader per
-- story (taking the like back removes it); one per comment.
CREATE TABLE notifications (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('like', 'comment')),
  story_id TEXT NOT NULL,
  actor_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  comment_id TEXT,
  created_at INTEGER NOT NULL,
  read_at INTEGER
);
CREATE INDEX notifications_user ON notifications(user_id, id);
CREATE UNIQUE INDEX notifications_like_once ON notifications(user_id, story_id, actor_id) WHERE kind = 'like';

-- Accounts a reader blocked: their stories and comments are hidden from the blocker, and they can't notify them.
CREATE TABLE blocks (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, blocked_id)
);
