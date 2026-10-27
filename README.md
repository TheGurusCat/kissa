# Kissa

An Android app for swiping through news and Reddit stories Tinder-style — **text only, no images or video**.

Website: https://kissa.abpro.workers.dev (source in `site/`; deploy with `cd site && npx wrangler deploy`).

## Why
A calmer replacement for Instagram and other image-heavy social apps: something to do with your thumb that doesn't pull you into an endless visual feed.

## Core idea
- One card on screen at a time showing a Reddit text post (title + body, subreddit, score).
- Swipe right: like (liked cards are kept under Liked in the menu).
- Swipe left: skip. Until the first swipe, a hint under the card says which way does what.
- Only story-length text posts (300+ characters) from story subreddits like TrueOffMyChest, tifu and AITAH; no question subs where the content is in the comments.
- Quick-read subreddits (Showerthoughts, LifeProTips, YouShouldKnow, Jokes, DadJokes) keep short posts too, shown in larger centered type; add them from the chips on the Subreddits screen (`QUICK_READ_SUBREDDITS` in `data/RedditRepository.kt`).
- News articles too, from about 35 sites grouped into Local (Mazatlán: Mazatlán Interactivo, Línea Directa and a Google
  News search for the city), Center, Liberal, Conservative, World, Business, Tech,
  Science & health and Sports. The News sources screen switches a whole category or single sites on and off
  (`NEWS_FEEDS` in `data/NewsRepository.kt`). Sites marked "full" send whole articles; the rest send a headline and
  summary with a "Hold for full article ↗" link (a long press, so a stray tap never leaves the app). Feeds are read straight from the publishers.
- A News / Reddit / Stories switch above the cards picks what to deal; the ☰ menu picks the Reddit story length (All, Quick or Long). Stories are written by Kissa's readers, newest first, with upvotes, comments and profiles.
- Share (top bar, or on a Liked item) draws the card as an image (`share/CardSharer.kt`) and opens Android's share
  sheet with the image plus the headline and link, so it works for Instagram as well as WhatsApp, X and Messages.
- Optional account (☰ → Account): sign in with a 6-digit code sent by email, and liked cards, sources, settings and the
  swiped history sync across phones (`data/AccountRepository.kt`, `data/AccountSync.kt`, `relay/src/account.js`).
- Text size is one setting: pinch a card with two fingers or use A−/A+ in Settings.
- Removing a saved post shows an Undo.
- ↶ at the top left undoes the last swipe (up to 30).
- Menu screens (Liked, News sources, Subreddits, Stats, Settings) swipe away to the right to go back to the cards
  (`ui/SwipeBack.kt`); Android's back gesture slides them the same way.
- ☰ menu, top right: Swipe, Liked, News sources, Subreddits, Stats (stories and minutes read per day), Settings
  (text size, serif font, NSFW stories, download stories for offline reading).
- Liked cards, swiped history (so stories don't repeat, crossposts and shared articles included), the unread deck (offline cache) and stats are kept on the phone.

## Stack
- Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35
- Reddit's official API, reached through a small Cloudflare Worker relay (`relay/`) that keeps the Reddit app secret off the phone
- Local storage: JSON files in the app's private folder plus SharedPreferences for settings (`data/LocalStore.kt`)

## Building on GitHub
- Every push to `main` builds a debug APK (Actions → Build → artifact `Kissa-debug`).
- **Releasing a new version:** bump nothing in code, just tag and push:
  ```bash
  git tag v0.2 && git push origin v0.2
  ```
  The Release workflow builds a signed APK with that version and attaches it to a GitHub Release.

## Auto-update
On launch the app checks the latest release of `TheGurusCat/kissa`. If its version is newer, it offers
**Update**, downloads the APK, and opens Android's installer. The first time, Android asks you to let
Kissa install apps; after that each update is one confirm tap. (Fully silent updates aren't possible
for apps outside the Play Store.)

Install v0.1 from the GitHub Release (not the debug artifact): updates only install over an app signed with
the same release key. The key lives outside this repo in `~/Desktop/Codex/local-code/reddit-swipe-signing/` (the same key the old app used). Back it up.

## Running it from Android Studio
1. Install [Android Studio](https://developer.android.com/studio) (it bundles Java and the Android SDK).
2. **File → Open** and pick this `kissa` folder. Let the Gradle sync finish.
3. Plug in your phone with USB debugging on (or start an emulator) and press **Run**.

## Code map
- `app/src/main/java/com/theguruscat/kissa/data/RedditRepository.kt`: fetches listings and keeps only story-length text posts, flagging NSFW ones
- `data/NewsRepository.kt`: reads the news sites' RSS/Atom feeds and turns articles into plain-text cards
- `SwipeViewModel.kt`: the card deck, saved list, NSFW setting, and the subreddits to pull from (edit `storySubreddits` / `nsfwSubreddits`)
- `ui/SwipeCard.kt`: the story card (typography, SAVE/SKIP stamps) and `CardSwipeState` (swipe thresholds and decisions)
- `ui/SwipeScreen.kt`: card stack, read-mode switch, drag/fling gestures and exit animations
- `ui/FeedEndCard.kt`: what sits under the last card: loading placeholder, error, no subreddits, or end of feed
- `ui/SavedScreen.kt`: saved posts; tap one to open it on Reddit

## Reddit relay (`relay/`)
Reddit blocks unauthenticated requests (HTTP 403) and no longer lets new API apps be created without
approval, so the app talks to a Cloudflare Worker at `https://kissa-relay.abpro.workers.dev`.
The Worker uses an existing Reddit "web app" (client credentials) whose ID and secret are stored as
Worker secrets, never in this repo. It only serves subreddit listings (`GET /r/<sub>/hot|new|top|rising`)
and caches responses for 2 minutes.

It also serves the optional accounts (`relay/src/account.js`): sign-in codes are emailed with Cloudflare Email Service
from `signin@wannabeguru.com` (the relay runs in the Andrebellapro account, which owns that domain). Accounts live in the `kissa` D1 (SQL) database: `users` (email, sources, settings),
`sessions`, `sign_in_codes`, `likes` (one row per liked card) and `daily_stats` (stories and seconds per day).
The app still syncs one JSON snapshot; the relay splits it into those tables. Schema: `relay/migrations/`.
Setup, once:
1. Cloudflare dashboard → Compute → Email Service → Email Sending → Onboard Domain → wannabeguru.com.
2. Sending to any address needs the Workers Paid plan; on the free plan only verified addresses receive codes.
3. Secrets on `kissa-relay`: `REDDIT_CLIENT_ID` and `REDDIT_CLIENT_SECRET`.

Redeploy after changing it, and apply new migrations:
```bash
cd relay && npx wrangler deploy
```
```bash
cd relay && npx wrangler d1 migrations apply kissa --remote
```
Kissa's own stories (the Stories tab) are written by its readers, Reddit-style (`relay/src/stories.js`): anyone signed
in with a username can post, upvote (a right swipe), comment and report. Each story also has a web page at `/s/<id>`.
Sign-in is a 6-digit email code or "Continue with Google"; usernames are picked on the Account screen. Admins
(`users.is_admin`) get a **Moderation** screen with open reports: remove the story or comment, dismiss, or ban the author.
Limits per account per day: 10 stories, 200 comments, 50 reports. Make someone an admin:
```bash
cd relay && npx wrangler d1 execute kissa --remote --command "UPDATE users SET is_admin = 1 WHERE email = 'you@example.com'"
```
Writers hear about likes and comments on their stories: the relay keeps `notifications`, the app shows them on a
**Notifications** screen, and about hourly in the background (WorkManager, `notify/ActivityNotifier.kt`) as Android
notifications. Readers can block an account (from its profile or a comment's menu), which hides its stories and comments
from them. **Account → Delete account** removes the account and everything it posted or synced (`DELETE /me`).
Public pages for the app stores: `/terms`, `/privacy`, `/delete-account` (`relay/src/pages.js`; contact address in
`CONTACT_EMAIL` in `relay/wrangler.toml`). Story pages carry link-preview tags and a "Read more stories on Kissa" link.

### Admin API (house accounts)
Kissa's own scripts can create **house accounts** and post stories and comments as them (`relay/src/api.js`). Readers
see a **KISSA** badge next to those names. House accounts can't sign in, and the API can't post as a reader's account.
Every request needs the `ADMIN_API_KEY` Worker secret in an `X-Kissa-Key` header; the key is kept outside the repo in
`~/Desktop/Codex/local-code/kissa-secrets/admin-api-key.txt`.

| Request | Body | Does |
|---|---|---|
| `GET /api/users` | | Lists house accounts with their story and comment counts |
| `POST /api/users` | `{username}` | Creates a house account |
| `POST /api/stories` | `{username, title, body}` | Posts a story; returns `{story}` (its `id` is `k_…`) |
| `POST /api/comments` | `{username, storyId, body}` | Comments on a story |
| `DELETE /api/stories/<id>`, `/api/comments/<id>` | | Removes one |

```bash
KEY=$(cat ~/Desktop/Codex/local-code/kissa-secrets/admin-api-key.txt)
curl -X POST https://kissa-relay.abpro.workers.dev/api/users -H "X-Kissa-Key: $KEY" -d '{"username":"KissaTales"}'
curl -X POST https://kissa-relay.abpro.workers.dev/api/stories -H "X-Kissa-Key: $KEY" \
  -d '{"username":"KissaTales","title":"The night shift","body":"First paragraph.\n\nSecond paragraph."}'
```
To change the key, write a new one to that file and run `npx wrangler secret put ADMIN_API_KEY` in `relay/`.

Google sign-in needs a Google Cloud project with an OAuth consent screen, a **Web application** client (its ID goes in
`GOOGLE_CLIENT_ID` in both `relay/wrangler.toml` and `app/build.gradle.kts`) and an **Android** client for package
`com.theguruscat.kissa` with the release key's SHA-1. Until then the button stays hidden.

Query the data, for example the most liked subreddits:
```bash
cd relay && npx wrangler d1 execute kissa --remote --command "SELECT subreddit, count(*) FROM likes GROUP BY 1 ORDER BY 2 DESC LIMIT 10"
```

## Ideas for later
- Keep saved posts on the device (Room)
- Let the user choose subreddits in the app
- Daily swipe limit / "take a break" reminder
