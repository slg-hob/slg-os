// Stub resource server (WP3.4): validates Keycloak access tokens (signature, issuer, audience, expiry)
// and checks a permission derived from the AD-style group claim. /echo shows headers the proxy forwards (WP1.11).
import { createServer } from 'node:http';
import { createRemoteJWKSet, jwtVerify } from 'jose';

const ISSUER = 'https://localhost:8443/auth/realms/slg';
const jwks = createRemoteJWKSet(new URL('http://127.0.0.1:8080/auth/realms/slg/protocol/openid-connect/certs'));
// Permission model stub: finance may use module A, ops may use module B.
const GROUP_PERMS = { '/finance': ['mod-a.data.read'], '/ops': ['mod-b.data.read'] };
const send = (res, code, body) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };

async function authorize(req, audience, permission) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  if (!m) return { code: 401, error: 'missing_token' };
  let payload;
  try {
    ({ payload } = await jwtVerify(m[1], jwks, { issuer: ISSUER, audience, clockTolerance: 60 }));
  } catch (e) {
    return { code: 401, error: `invalid_token: ${e.code || e.message}` };
  }
  const perms = (payload.groups || []).flatMap((g) => GROUP_PERMS[g] || []);
  if (!perms.includes(permission)) return { code: 403, error: 'forbidden', groups: payload.groups };
  return { code: 200, sub: payload.sub, groups: payload.groups, aud: payload.aud };
}

createServer(async (req, res) => {
  const path = req.url.split('?')[0];
  if (path === '/echo') return send(res, 200, { headers: req.headers, remote: req.socket.remoteAddress });
  const m = /^\/(mod-a|mod-b)\/data$/.exec(path);
  if (m) {
    const r = await authorize(req, m[1], `${m[1]}.data.read`);
    return send(res, r.code, r);
  }
  send(res, 404, { error: 'not_found' });
}).listen(5300, '127.0.0.1');
