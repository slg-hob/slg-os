import { Component, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ShellProvider } from 'spike-sdk';
import { shellMessages, loadModuleBundle, lookup } from './i18n.js';
import { fetchRegistry, loadModuleComponent } from './federation.js';
import { initAuth, getAccessToken, signOut, mgr } from './auth.js';
import WindowManager from './wm/WindowManager.jsx';

// Only non-secret profile data goes into React state: the fiber tree is readable by every module (WP3.6 finding).
const publicUser = (u) => ({ profile: { preferred_username: u.profile.preferred_username, sub: u.profile.sub } });

class WindowBoundary extends Component {
  state = { error: null };
  static getDerivedStateFromError(error) {
    return { error };
  }
  render() {
    if (this.state.error) {
      const { s, onRetry } = this.props;
      return (
        <div role="alert" className="p-3" data-testid="window-error">
          <p className="text-danger mb-2">{s.failed}</p>
          <pre className="small">{String(this.state.error.message)}</pre>
          <button className="btn btn-outline-secondary btn-sm" onClick={() => { this.setState({ error: null }); onRetry(); }}>{s.retry}</button>
        </div>
      );
    }
    return this.props.children;
  }
}

// Content of one window. The module component loads when the window is first visible (lazy restore, UI-6).
function ModuleContent({ w, m, visible, shell, s, guardRef }) {
  const [Comp, setComp] = useState(null);
  const [error, setError] = useState(null);
  const [attempt, setAttempt] = useState(0);
  const seen = useRef(false);
  if (visible) seen.current = true;

  useEffect(() => {
    if (!seen.current && !visible) return undefined;
    let alive = true;
    setComp(null);
    setError(null);
    Promise.all([loadModuleComponent(m), loadModuleBundle(m.id, shell.language)])
      .then(([C]) => alive && setComp(() => C))
      .catch((e) => alive && setError(e));
    return () => { alive = false; };
  }, [m, attempt, visible && true]); // language changes must not remount the module (state is kept, UI-25)

  const bound = useMemo(
    () => ({ ...shell, getAccessToken: () => getAccessToken(`mod-${m.id}`), setCloseGuard: (fn) => guardRef.current?.set(w.id, fn) }),
    [shell, m.id, w.id, guardRef]
  );

  if (error) {
    return (
      <div role="alert" className="p-3" data-testid="window-error">
        <p className="text-danger mb-2">{s.failed}</p>
        <pre className="small">{String(error.message)}</pre>
        <button className="btn btn-outline-secondary btn-sm" onClick={() => setAttempt((a) => a + 1)}>{s.retry}</button>
      </div>
    );
  }
  if (!Comp) return <p className="p-3 mb-0">{s.loading}</p>;
  return (
    <WindowBoundary s={s} onRetry={() => setAttempt((a) => a + 1)}>
      <ShellProvider value={bound}>
        <Comp />
      </ShellProvider>
    </WindowBoundary>
  );
}

export default function App() {
  const [auth, setAuth] = useState({ status: 'init' });
  useEffect(() => {
    initAuth().then((r) => setAuth(r.user ? { status: 'ready', user: publicUser(r.user) } : { status: r.frame ? 'frame' : 'redirect' }))
      .catch((e) => setAuth({ status: 'error', error: String(e.message) }));
    const onExpired = () => setAuth((a) => ({ ...a, status: 'expired' }));
    const onLoaded = (user) => setAuth({ status: 'ready', user: publicUser(user) });
    mgr.events.addAccessTokenExpired(onExpired);
    mgr.events.addUserLoaded(onLoaded);
    return () => { mgr.events.removeAccessTokenExpired(onExpired); mgr.events.removeUserLoaded(onLoaded); };
  }, []);
  if (auth.status === 'ready' || auth.status === 'expired') return <Desktop auth={auth} />;
  if (auth.status === 'error') return <p role="alert" data-testid="auth-error">{auth.error}</p>;
  return <p data-testid="auth-pending">…</p>;
}

function Desktop({ auth }) {
  const [theme, setTheme] = useState('light');
  const [language, setLanguage] = useState('en');
  const [registry, setRegistry] = useState({ accepted: [], rejected: [] });
  const [, bump] = useState(0);
  const guardApi = useRef(null);
  const registerGuard = useCallback((api) => { guardApi.current = api; }, []);
  const s = shellMessages[language];
  // Engine is chosen with ?wm=rnd and remembered for the session (deep links drop the query string).
  const q = new URLSearchParams(location.search).get('wm');
  if (q) sessionStorage.setItem('spike.engine', q);
  const engine = sessionStorage.getItem('spike.engine') === 'rnd' ? 'rnd' : 'inhouse';

  useEffect(() => { document.documentElement.setAttribute('data-bs-theme', theme); }, [theme]);
  useEffect(() => { document.documentElement.lang = language; }, [language]);
  useEffect(() => { fetchRegistry().then(setRegistry); }, []);

  // Load module bundles for the language on switch so the change applies without a reload.
  useEffect(() => {
    Promise.all(registry.accepted.map((m) => loadModuleBundle(m.id, language).catch(() => null))).then(() => bump((n) => n + 1));
  }, [language, registry.accepted]);

  const t = useCallback((ns, key) => lookup(ns, language, key), [language]);
  const shell = useMemo(() => ({ t, theme, language, getAccessToken }), [t, theme, language]);

  const nav = ({ openModule }) => (
    <nav className="navbar navbar-expand bg-body-tertiary px-3 flex-wrap gap-2" aria-label={s.launcher}>
      <span className="navbar-brand">SLG OS spike</span>
      <ul className="navbar-nav me-auto flex-wrap" data-testid="launcher">
        {registry.accepted.length === 0 && <li className="nav-item text-body-secondary">{s.noModules}</li>}
        {registry.accepted.map((m) => (
          <li className="nav-item" key={m.id}>
            <button className="btn btn-link nav-link" onClick={() => openModule(m.id)} data-testid={`open-${m.id}`}>
              {s.open}: {m.name[language] ?? m.name.en}
            </button>
          </li>
        ))}
      </ul>
      <div className="d-flex gap-2 align-items-center">
        <span className="navbar-text" data-testid="user">{auth.user.profile.preferred_username}</span>
        <button className="btn btn-sm btn-outline-secondary" data-testid="sign-out" onClick={() => signOut()}>{s.signOut}</button>
        <button className="btn btn-sm btn-outline-secondary" data-testid="toggle-theme" onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
          {s.theme}: {theme === 'light' ? s.light : s.dark}
        </button>
        <button className="btn btn-sm btn-outline-secondary" data-testid="toggle-lang" onClick={() => setLanguage(language === 'en' ? 'de' : 'en')}>
          {s.language}: {language.toUpperCase()}
        </button>
      </div>
    </nav>
  );

  return (
    <ShellProvider value={shell}>
      {auth.status === 'expired' && (
        <div className="alert alert-warning m-0 rounded-0" role="alert" data-testid="session-expired">
          {s.sessionExpired} <button className="btn btn-link p-0" onClick={() => mgr.signinSilent().catch(() => mgr.signinRedirect())}>{s.signInAgain}</button>
        </div>
      )}
      {registry.rejected.length > 0 && (
        <div className="alert alert-warning m-0 rounded-0" data-testid="rejected">{registry.rejected.map((r) => `${r.id}: ${r.error}`).join('; ')}</div>
      )}
      <WindowManager
        modules={registry.accepted}
        language={language}
        s={s}
        engine={engine}
        nav={nav}
        registerGuard={registerGuard}
        renderContent={(w, m, visible) => <ModuleContent w={w} m={m} visible={visible} shell={shell} s={s} guardRef={guardApi} />}
      />
    </ShellProvider>
  );
}
