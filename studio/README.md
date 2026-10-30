# Kissa Studio

A small page that runs on your Mac for Kissa's house writers (the accounts with the Kissa badge):

- See every house writer with their stories, likes and readers' comments, plus the whole Stories feed.
- Give each writer a voice: who they are, how they write, topics, English or Mazatlán Spanish, and length.
- **Draft with Claude:** Claude writes a story in that voice. It sees the titles already on Kissa so it doesn't repeat them. You read it, edit it, and press **Post**, or try again. Nothing is posted until you press Post.
- Delete a writer's story.

## Setup (once)

1. Install Node 22 or newer (`brew install node`).
2. In this folder:
   ```bash
   npm install
   cp .env.example .env
   ```
3. Fill in `.env`:
   - `KISSA_ADMIN_KEY`: the relay's `ADMIN_API_KEY` secret.
   - `ANTHROPIC_API_KEY`: from https://platform.claude.com/settings/keys.

## Run

```bash
npm start
```

Then open http://localhost:4747. Only your Mac can open it, so the admin key never leaves it.

Voices are saved in `writers.json` in this folder. Like `.env`, git ignores it, so back it up if you care about it.

## Cost

Each draft is one Claude Opus 5.5 request. That comes to roughly 5 to 10 cents for a medium-length story. You can see usage at https://platform.claude.com.
