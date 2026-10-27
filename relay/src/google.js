// Checks a Google ID token from "Continue with Google" in the app (Android Credential Manager) and returns
// the verified email address. The token must be signed by Google, unexpired, and issued for GOOGLE_CLIENT_ID
// (the Web client ID from Google Cloud that the app also uses).

const CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = ["accounts.google.com", "https://accounts.google.com"];

export async function verifiedGoogleEmail(idToken, env) {
  const parts = String(idToken ?? "").split(".");
  if (parts.length !== 3) return null;
  let header, claims;
  try {
    header = JSON.parse(text(base64url(parts[0])));
    claims = JSON.parse(text(base64url(parts[1])));
  } catch {
    return null;
  }
  if (header.alg !== "RS256") return null;
  const jwk = (await googleKeys()).find((key) => key.kid === header.kid);
  if (!jwk) return null;
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, base64url(parts[2]), signed))) return null;
  const now = Date.now() / 1000;
  if (!ISSUERS.includes(claims.iss) || claims.aud !== env.GOOGLE_CLIENT_ID || !(claims.exp > now)) return null;
  if (claims.email_verified !== true || typeof claims.email !== "string") return null;
  return claims.email;
}

/** Google's current signing keys; Cloudflare caches the response for an hour. */
async function googleKeys() {
  const response = await fetch(CERTS_URL, { cf: { cacheTtl: 3600, cacheEverything: true } });
  if (!response.ok) throw new Error(`Google keys: HTTP ${response.status}`);
  return (await response.json()).keys ?? [];
}

function base64url(part) {
  const base64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function text(bytes) {
  return new TextDecoder().decode(bytes);
}
