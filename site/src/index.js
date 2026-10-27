// Kissa's website (https://kissa.abpro.workers.dev): what the app is, a download button for the newest APK, and
// the latest community stories. Stories come from kissa-relay through a service binding; the APK link from
// GitHub's latest release. Both are cached for a few minutes. Terms, privacy and story pages live on the relay.
//
//   GET /           the home page
//   GET /download   redirects to the newest Kissa-<version>.apk

const CACHE_SECONDS = 300;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405 });
    if (url.pathname === "/download") {
      const release = await latestRelease(env, ctx);
      return Response.redirect(release?.apk ?? `https://github.com/${env.GITHUB_REPO}/releases/latest`, 302);
    }
    if (url.pathname === "/" || url.pathname === "/index.html") {
      const [release, stories] = await Promise.all([latestRelease(env, ctx), latestStories(env)]);
      return new Response(home(env, release, stories), {
        headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": `public, max-age=${CACHE_SECONDS}` },
      });
    }
    return new Response(notFound(), { status: 404, headers: { "Content-Type": "text/html; charset=utf-8" } });
  },
};

/** {version, apk, size} of the newest GitHub release, or null if GitHub can't be reached. */
async function latestRelease(env, ctx) {
  const api = `https://api.github.com/repos/${env.GITHUB_REPO}/releases/latest`;
  const cache = caches.default;
  const key = new Request(api);
  let response = await cache.match(key);
  if (!response) {
    response = await fetch(api, { headers: { "User-Agent": "kissa-site", Accept: "application/vnd.github+json" } });
    if (!response.ok) return null;
    const fresh = await response.json();
    // A release is published a moment before its APK is attached; don't keep that in the cache.
    const ready = (fresh.assets ?? []).some((a) => a.name.endsWith(".apk"));
    response = new Response(JSON.stringify(fresh), { headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${CACHE_SECONDS}` } });
    if (ready) ctx.waitUntil(cache.put(key, response.clone()));
  }
  const release = await response.json();
  const asset = (release.assets ?? []).find((a) => a.name.endsWith(".apk"));
  return { version: String(release.tag_name ?? "").replace(/^v/, ""), apk: asset?.browser_download_url, size: asset?.size };
}

async function latestStories(env) {
  try {
    const response = await env.RELAY.fetch(`${env.RELAY_URL}/stories?limit=6`);
    return response.ok ? (await response.json()).stories : [];
  } catch {
    return [];
  }
}

function home(env, release, stories) {
  const version = release?.version ? `Version ${escape(release.version)}` : "Latest version";
  const size = release?.size ? ` · ${(release.size / 1_048_576).toFixed(0)} MB` : "";
  const storyCards = stories.map((story) => `
      <a class="story" href="${escape(story.permalink)}">
        <span class="who">u/${escape(story.author)}${story.isHouse ? ' <b class="badge">KISSA</b>' : ""}</span>
        <span class="title">${escape(story.title)}</span>
        <span class="body">${escape(clip(story.body, 140))}</span>
        <span class="meta">♥ ${story.score} · ${story.numComments} ${story.numComments === 1 ? "comment" : "comments"}</span>
      </a>`).join("");
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kissa · read, don't scroll</title>
<meta name="description" content="Kissa is a calm Android app for reading: one text card at a time from Reddit, the news and stories by its readers. No images, no endless feed, no ads.">
<meta property="og:title" content="Kissa · read, don't scroll">
<meta property="og:description" content="One text card at a time: Reddit, the news, and stories by its readers. No images, no ads.">
<meta property="og:type" content="website">
<style>
  :root { --bg: #faf8f5; --card: #ffffff; --ink: #1d1b19; --muted: #6b655e; --line: #e7e1d9; --accent: #b4532a; --on-accent: #fffaf5; color-scheme: light dark; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121110; --card: #1c1a18; --ink: #ece7e1; --muted: #9a938b; --line: #2c2926; --accent: #e08a5f; --on-accent: #1a0f08; } }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 17px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif; }
  a { color: inherit; }
  .wrap { max-width: 1040px; margin: 0 auto; padding: 0 16px; }
  header { display: flex; align-items: center; justify-content: space-between; padding: 20px 0; }
  .logo { font: 700 15px/1 system-ui, sans-serif; letter-spacing: .12em; text-transform: uppercase; color: var(--accent); text-decoration: none; }
  header nav a { margin-left: 18px; font-size: 15px; color: var(--muted); text-decoration: none; }
  .hero { display: grid; grid-template-columns: 1.15fr .85fr; gap: 48px; align-items: center; padding: 48px 0 72px; }
  h1 { font: 600 clamp(40px, 6vw, 64px)/1.05 Georgia, "Times New Roman", serif; margin: 0 0 20px; letter-spacing: -.01em; }
  .lead { font-size: 20px; color: var(--muted); max-width: 30em; margin: 0 0 32px; }
  .btn { display: inline-flex; align-items: center; gap: 10px; padding: 14px 24px; border-radius: 999px; background: var(--accent); color: var(--on-accent); font-weight: 600; text-decoration: none; }
  .btn:hover { filter: brightness(1.05); }
  .fine { margin-top: 12px; font-size: 14px; color: var(--muted); }
  .phone { justify-self: center; width: 280px; aspect-ratio: 9 / 18.5; border-radius: 40px; padding: 14px; background: #161412; outline: 1px solid var(--line); box-shadow: 0 30px 60px -20px rgba(0,0,0,.35); }
  .screen { height: 100%; border-radius: 28px; background: var(--bg); padding: 18px 14px; display: flex; flex-direction: column; gap: 12px; overflow: hidden; }
  .tabs { display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid var(--line); border-radius: 999px; font-size: 12px; text-align: center; }
  .tabs span { padding: 6px 0; }
  .tabs .on { background: var(--line); border-radius: 999px; font-weight: 600; }
  .card { flex: 1; background: var(--card); border-radius: 22px; padding: 18px; display: flex; flex-direction: column; box-shadow: 0 6px 18px -8px rgba(0,0,0,.25); transform: rotate(-3deg) translateX(-6px); }
  .card .top { display: flex; justify-content: space-between; font-size: 12px; color: var(--accent); font-weight: 600; }
  .card .top i { font-style: normal; color: var(--muted); font-weight: 500; }
  .card h3 { font: 600 21px/1.25 Georgia, serif; margin: 14px 0 10px; }
  .card p { margin: 0; font: 14px/1.55 Georgia, serif; color: var(--muted); }
  .card .foot { margin-top: auto; font-size: 11px; color: var(--muted); }
  .hint { text-align: center; font-size: 12px; color: var(--muted); }
  section { padding: 56px 0; border-top: 1px solid var(--line); }
  h2 { font: 600 30px/1.2 Georgia, serif; margin: 0 0 28px; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; }
  .tile { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 22px; }
  .tile h3 { margin: 0 0 6px; font-size: 18px; }
  .tile p { margin: 0; color: var(--muted); font-size: 15px; }
  .stories { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
  .story { display: flex; flex-direction: column; gap: 6px; background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 18px; text-decoration: none; }
  .story:hover { border-color: var(--accent); }
  .story .who { font-size: 13px; color: var(--accent); font-weight: 600; }
  .badge { font-size: 10px; letter-spacing: .08em; padding: 1px 6px; border-radius: 6px; border: 1px solid currentColor; margin-left: 4px; }
  .story .title { font: 600 18px/1.3 Georgia, serif; }
  .story .body { font-size: 14px; color: var(--muted); }
  .story .meta { margin-top: auto; font-size: 13px; color: var(--muted); }
  ol.steps { padding-left: 20px; max-width: 40em; }
  ol.steps li { margin: 8px 0; }
  footer { padding: 32px 0 48px; border-top: 1px solid var(--line); font-size: 14px; color: var(--muted); display: flex; flex-wrap: wrap; gap: 18px; }
  footer a { color: var(--muted); }
  @media (max-width: 820px) {
    .hero { grid-template-columns: 1fr; padding-top: 24px; gap: 40px; }
    .grid, .stories { grid-template-columns: 1fr; }
    header nav a:not(:last-child) { display: none; }
  }
</style></head>
<body><div class="wrap">
<header><a class="logo" href="/">Kissa</a>
  <nav><a href="#how">How it works</a><a href="#stories">Stories</a><a href="/download">Download</a></nav></header>

<div class="hero">
  <div>
    <h1>Read, don't scroll.</h1>
    <p class="lead">Kissa shows one text card at a time: a Reddit story, a news article, or something a reader wrote. Swipe right to like, left to skip. No images, no video, no endless feed, no ads.</p>
    <a class="btn" href="/download">Download for Android</a>
    <div class="fine">${version}${size} · free · Android 8 or newer</div>
  </div>
  <div class="phone" aria-hidden="true"><div class="screen">
    <div class="tabs"><span>News</span><span>Reddit</span><span class="on">Stories</span></div>
    <div class="card">
      <div class="top"><span>u/ShowerThinker</span><i>♥ 128</i></div>
      <h3>Your future self is just you, but with more context</h3>
      <p>Every decision today is a note you're leaving for them.</p>
      <div class="foot">Quick read · 12 comments</div>
    </div>
    <div class="hint">← skip · like →</div>
  </div></div>
</div>

<section id="how"><h2>How it works</h2><div class="grid">
  <div class="tile"><h3>Three tabs</h3><p><b>News</b> from about 35 outlets across the spectrum, <b>Reddit</b> stories from the subreddits you pick, and <b>Stories</b> written by Kissa's readers.</p></div>
  <div class="tile"><h3>One card at a time</h3><p>Swipe right to like, left to skip. Pinch to change the text size. Liked cards are saved for later, and you never see the same card twice.</p></div>
  <div class="tile"><h3>Write your own</h3><p>Sign in with Google or your email, pick a username and post. Readers like and comment, and you get notified.</p></div>
  <div class="tile"><h3>Calm by design</h3><p>Text only, so it's quick to read and easy to put down. Daily stats show how much you read.</p></div>
  <div class="tile"><h3>Yours on every phone</h3><p>An optional account keeps your likes, sources and settings in sync.</p></div>
  <div class="tile"><h3>Private</h3><p>No ads, no trackers, nothing sold. Delete your account anytime from the app.</p></div>
</div></section>

${stories.length ? `<section id="stories"><h2>Latest stories on Kissa</h2><div class="stories">${storyCards}
</div></section>` : ""}

<section id="install"><h2>Installing</h2><ol class="steps">
  <li>Tap <a href="/download">Download for Android</a> on your phone.</li>
  <li>Open the downloaded file. If Android asks, allow your browser to install apps.</li>
  <li>Kissa updates itself: when a new version is out, the app offers it and installs with one tap.</li>
</ol><p class="fine">Kissa isn't on the Play Store or the App Store yet; there's no iPhone version for now.</p></section>

<footer>
  <span>© ${new Date().getFullYear()} Kissa</span>
  <a href="${env.RELAY_URL}/terms">Terms</a>
  <a href="${env.RELAY_URL}/privacy">Privacy</a>
  <a href="${env.RELAY_URL}/delete-account">Delete your account</a>
  <a href="https://github.com/${env.GITHUB_REPO}">Source on GitHub</a>
</footer>
</div></body></html>`;
}

function notFound() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Not found · Kissa</title><style>body{margin:0;font:17px/1.6 system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;background:#faf8f5;color:#1d1b19}
@media (prefers-color-scheme: dark){body{background:#121110;color:#ece7e1}}a{color:#b4532a}</style></head>
<body><p>Nothing here. <a href="/">Go to Kissa</a></p></body></html>`;
}

function clip(text, max) {
  const flat = String(text ?? "").replace(/\s+/g, " ").trim();
  return flat.length > max ? flat.slice(0, max).trimEnd() + "…" : flat;
}

function escape(text) {
  return String(text ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
