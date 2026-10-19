import { UserManager, WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts';

// WP3: OIDC authorization code + PKCE (S256). Tokens live in memory only (Q11); only the OIDC state/nonce
// for the redirect round trip is kept in sessionStorage (library requirement, no tokens).
const origin = location.origin;

export const mgr = new UserManager({
  authority: `${origin}/auth/realms/slg`,
  client_id: 'shell',
  redirect_uri: `${origin}/`,
  silent_redirect_uri: `${origin}/`,
  post_logout_redirect_uri: `${origin}/`,
  response_type: 'code',
  scope: 'openid groups mod-a mod-b',
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  refreshTokenAllowedScope: 'openid groups', // library-driven renewals never widen the shell token to module audiences
  automaticSilentRenew: true,
  accessTokenExpiringNotificationTimeInSeconds: 120, // UI-13: renew 2 minutes before expiry
  silentRequestTimeoutInSeconds: 10,
});

// The login requests the module scopes so they can be granted; the shell's own token is immediately narrowed
// to the core scopes so no broad token with every module audience stays in use.
async function narrow(user) {
  const meta = await mgr.metadataService.getMetadata();
  const res = await fetch(meta.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', client_id: 'shell', refresh_token: user.refresh_token, scope: 'openid groups' }),
  });
  if (!res.ok) throw new Error(`narrowing failed: ${res.status}`);
  const body = await res.json();
  user.access_token = body.access_token;
  user.refresh_token = body.refresh_token;
  user.expires_at = Math.floor(Date.now() / 1000) + body.expires_in;
  await mgr.storeUser(user);
  return user;
}

export async function initAuth() {
  const r = await initAuthRaw();
  if (r.user && !r.frame) r.user = await narrow(r.user);
  return r;
}

async function initAuthRaw() {
  const qs = new URLSearchParams(location.search);
  // Silent renewal iframe (prompt=none) lands on the redirect URI inside a frame.
  if (window.self !== window.top && (qs.has('code') || qs.has('error'))) {
    await mgr.signinSilentCallback();
    return { frame: true };
  }
  if (qs.has('code') && qs.has('state')) {
    const user = await mgr.signinCallback();
    history.replaceState({}, '', '/');
    return { user };
  }
  let user = await mgr.getUser(); // always null after a reload: nothing is persisted
  if (!user) {
    try {
      user = await mgr.signinSilent(); // SSO cookie of Keycloak re-establishes the session without interaction
    } catch {
      await mgr.signinRedirect();
      return { redirecting: true };
    }
  }
  return { user };
}

// WP3.5 finding: Keycloak refuses standard token exchange for a public client ("Public client is not allowed
// to exchange token"). Per-module audience is therefore obtained by refresh-token down-scoping: the login requests the
// optional module scopes, and each module token is a refresh grant with scope=<module>. Refresh tokens rotate, so
// all grants are serialized here; a second concurrent grant would trigger reuse detection (see WP3.7).
const cache = new Map();
let chain = Promise.resolve();

async function refreshFor(audience) {
  const user = await mgr.getUser();
  if (!user) throw new Error('not signed in');
  const hit = cache.get(audience);
  if (hit && hit.exp - 30 > Date.now() / 1000) return hit.token;
  const meta = await mgr.metadataService.getMetadata();
  const res = await fetch(meta.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', client_id: 'shell', refresh_token: user.refresh_token, scope: `openid ${audience}` }),
  });
  if (!res.ok) throw new Error(`token for ${audience} failed: ${res.status} ${await res.text()}`);
  const body = await res.json();
  user.refresh_token = body.refresh_token; // rotated; keep the library's copy in sync
  await mgr.storeUser(user);
  cache.set(audience, { token: body.access_token, exp: Math.floor(Date.now() / 1000) + body.expires_in });
  return body.access_token;
}

export async function getAccessToken(audience) {
  if (!audience) return (await mgr.getUser())?.access_token;
  const p = chain.then(() => refreshFor(audience));
  chain = p.catch(() => {});
  return p;
}

export const signOut = () => mgr.signoutRedirect();

// Spike-only test hook (never in production): exposes the user manager for renewal-matrix tests.
if (new URLSearchParams(location.search).has('debug')) window.__spike = { mgr, getAccessToken };
