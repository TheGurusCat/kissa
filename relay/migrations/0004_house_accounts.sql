-- House accounts: run by Kissa through the admin API (src/api.js), not by a reader. Readers see a Kissa badge
-- next to their name. They have no real email (a placeholder under kissa.invalid) and can't sign in.
ALTER TABLE users ADD COLUMN is_house INTEGER NOT NULL DEFAULT 0;
