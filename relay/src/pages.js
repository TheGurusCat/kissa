// Kissa's public pages: terms, privacy policy and how to delete an account (app stores ask for all three).
// Story pages (/s/<id>) live in stories.js and share this look through pageShell.
//
//   GET /terms, /privacy, /delete-account

const UPDATED = "October 27, 2026";
export const DOWNLOAD_URL = "https://github.com/TheGurusCat/kissa/releases/latest";

export function handlePages(request, env, url) {
  if (request.method !== "GET") return null;
  const contact = env.CONTACT_EMAIL;
  const page = { "/terms": terms, "/privacy": privacy, "/delete-account": deleteAccount }[url.pathname];
  if (!page) return null;
  const { title, body } = page(contact);
  return new Response(pageShell({ title, content: `<h1>${title}</h1><div class="byline">Updated ${UPDATED}</div>\n${body}` }), {
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "public, max-age=3600" },
  });
}

function mail(contact) {
  return `<a href="mailto:${contact}">${contact}</a>`;
}

function terms(contact) {
  return {
    title: "Kissa terms of use",
    body: `
<p>Kissa is an app for reading short text: Reddit posts, news, and stories written by Kissa's readers. By using it you
agree to these terms.</p>
<h2>Your account</h2>
<p>Reading needs no account. To post, comment, like or report you sign in with Google or a code sent to your email,
and pick a username. You must be at least 13 years old, or older if your country requires it. Keep your sign-in to
yourself; you're responsible for what's posted from your account.</p>
<h2>What you post</h2>
<p>You keep the rights to your stories and comments. By posting, you let Kissa show them in the app and on Kissa's
web pages, and let readers share links to them. You can edit or delete your stories and comments at any time.</p>
<p>Don't post anything that:</p>
<ul>
<li>sexualizes minors in any way (we remove it and report it to the authorities),</li>
<li>harasses, threatens or encourages violence against anyone,</li>
<li>attacks people for who they are (race, religion, gender, sexuality, disability, origin),</li>
<li>shares someone's private information without their consent,</li>
<li>is spam, a scam, or copies someone else's work without permission,</li>
<li>breaks the law where you live.</li>
</ul>
<p>Readers can report stories and comments. Kissa may remove anything that breaks these rules and may stop an account
from posting. Accounts marked <b>KISSA</b> are run by Kissa itself.</p>
<h2>Other people's content</h2>
<p>Reddit posts belong to their authors and Reddit, and news articles to their publishers; Kissa only shows them and
links to the original. Blocking an account hides its stories and comments from you.</p>
<h2>No guarantees</h2>
<p>Kissa is provided as is, without warranties, and may change or stop. To the extent the law allows, Kissa isn't liable
for losses from using it or from what other people post.</p>
<h2>Changes and contact</h2>
<p>If these terms change, the date above changes too; continuing to use Kissa means you accept them. Questions or
complaints, including copyright notices: ${mail(contact)}.</p>`,
  };
}

function privacy(contact) {
  return {
    title: "Kissa privacy policy",
    body: `
<p>Kissa collects as little as it can. There are no ads, no ad trackers, and nothing is sold.</p>
<h2>Without an account</h2>
<p>Your liked cards, settings, sources and reading stats stay on your phone. Reddit posts come through Kissa's server
(without anything identifying you); news feeds are read by your phone straight from the publishers.</p>
<h2>With an account</h2>
<p>Kissa stores, on Cloudflare's servers:</p>
<ul>
<li>your email address (to sign you in) and your username,</li>
<li>what you post: stories, comments, likes and reports, and who you blocked,</li>
<li>what you sync between phones: liked cards, chosen subreddits and news sources, reading settings, recently swiped
cards, and daily reading time (stories read and minutes),</li>
<li>notifications about likes and comments on your stories,</li>
<li>sign-in sessions and codes, stored only as one-way hashes; codes expire after 10 minutes and sessions after a year.</li>
</ul>
<p>Your username, stories and comments are public. Your email address, synced data and reading time are not shown to
anyone else.</p>
<h2>Services Kissa uses</h2>
<ul>
<li><b>Cloudflare</b> hosts Kissa's server and database and sends sign-in emails.</li>
<li><b>Google</b>, if you choose "Continue with Google", tells Kissa your verified email address.</li>
<li><b>GitHub</b> hosts app updates; your phone checks it for new versions.</li>
</ul>
<h2>Deleting your data</h2>
<p>In the app, open the menu, then Account, then <b>Delete account</b>. That removes your account, stories, comments,
likes, synced data and reading stats right away. Or email ${mail(contact)} from your account's address and it will
be done within 30 days. See <a href="/delete-account">how to delete your account</a>.</p>
<h2>Children</h2>
<p>Kissa isn't meant for children under 13. If you believe a child has an account, email ${mail(contact)} and it will
be removed.</p>
<h2>Contact</h2>
<p>${mail(contact)}</p>`,
  };
}

function deleteAccount(contact) {
  return {
    title: "Delete your Kissa account",
    body: `
<h2>In the app</h2>
<ol>
<li>Open Kissa and tap the menu (☰).</li>
<li>Tap <b>Account</b>.</li>
<li>Tap <b>Delete account</b> and confirm.</li>
</ol>
<h2>By email</h2>
<p>If you no longer have the app, email ${mail(contact)} from the address you signed in with and ask to delete your
account. It will be done within 30 days.</p>
<h2>What gets deleted</h2>
<p>Your account, email address and username; every story and comment you posted, with their likes and comments; your
likes, reports and blocks; your synced liked cards, sources, settings and reading stats; your notifications and
sign-in sessions. Nothing is kept afterwards.</p>`,
  };
}

/** Kissa's web page look, shared by stories and these pages. [head] adds tags such as link previews. */
export function pageShell({ title, content, head = "" }) {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · Kissa</title>${head}
<style>
  :root { --bg: #faf8f5; --ink: #1d1b19; --muted: #6b655e; --accent: #b4532a; color-scheme: light dark; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121110; --ink: #ece7e1; --muted: #9a938b; --accent: #e08a5f; } }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 19px/1.65 Georgia, "Times New Roman", serif; }
  main { max-width: 640px; margin: 0 auto; padding: 40px 16px 64px; }
  .brand { font: 600 14px/1 system-ui, sans-serif; letter-spacing: .08em; text-transform: uppercase; color: var(--accent); text-decoration: none; }
  h1 { font-size: 30px; line-height: 1.25; margin: 16px 0 6px; }
  h2 { font: 600 18px/1.3 system-ui, sans-serif; margin: 32px 0 8px; }
  li { margin: 4px 0; }
  a { color: var(--accent); }
  .byline { font: 14px system-ui, sans-serif; color: var(--muted); margin-bottom: 28px; }
  .get { display: inline-block; margin-top: 40px; padding: 12px 20px; border-radius: 999px; background: var(--accent);
    color: var(--bg); font: 600 16px system-ui, sans-serif; text-decoration: none; }
  footer { margin-top: 48px; font: 13px system-ui, sans-serif; color: var(--muted); }
  footer a { color: var(--muted); }
</style></head>
<body><main><a class="brand" href="${DOWNLOAD_URL}">Kissa</a>
${content}
<footer><a href="/terms">Terms</a> · <a href="/privacy">Privacy</a></footer>
</main></body></html>`;
}
