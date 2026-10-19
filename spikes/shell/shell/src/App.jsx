import { Component, useCallback, useEffect, useMemo, useState } from 'react';
import { ShellProvider } from 'spike-sdk';
import { shellMessages, loadModuleBundle, lookup } from './i18n.js';
import { fetchRegistry, loadModuleComponent } from './federation.js';
import { initAuth, getAccessToken, signOut, mgr } from './auth.js';

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

function ModuleWindow({ m, shell, s, onClose }) {
  const [Comp, setComp] = useState(null);
  const [error, setError] = useState(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    setComp(null);
    setError(null);
    Promise.all([loadModuleComponent(m), loadModuleBundle(m.id, shell.language)])
      .then(([C]) => alive && setComp(() => C))
      .catch((e) => alive && setError(e));
    return () => { alive = false; };
  }, [m, attempt]); // language changes must not remount the module (state is kept, UI-25)

  return (
    <section className="card shadow-sm mb-3" aria-labelledby={`win-${m.id}`} data-testid={`window-${m.id}`}>
      <header className="card-header d-flex justify-content-between align-items-center">
        <h2 className="h6 mb-0" id={`win-${m.id}`}>{m.name[shell.language] ?? m.name.en}</h2>
        <button className="btn btn-sm btn-outline-secondary" onClick={onClose}>{s.close}</button>
      </header>
      <div className="card-body p-0">
        {error && (
          <div role="alert" className="p-3" data-testid="window-error">
            <p className="text-danger mb-2">{s.failed}</p>
            <pre className="small">{String(error.message)}</pre>
            <button className="btn btn-outline-secondary btn-sm" onClick={() => setAttempt((a) => a + 1)}>{s.retry}</button>
          </div>
        )}
        {!error && !Comp && <p className="p-3 mb-0">{s.loading}</p>}
        {!error && Comp && (
          <WindowBoundary s={s} onRetry={() => setAttempt((a) => a + 1)}>
            <ShellProvider value={{ ...shell, getAccessToken: () => getAccessToken(`mod-${m.id}`) }}>
              <Comp />
            </ShellProvider>
          </WindowBoundary>
        )}
      </div>
    </section>
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
  // UI-6 (spike): open windows are persisted per browser session and restored after reload.
  const [open, setOpen] = useState(() => JSON.parse(sessionStorage.getItem('spike.open') || '[]'));
  useEffect(() => { sessionStorage.setItem('spike.open', JSON.stringify(open)); }, [open]);
  const [, bump] = useState(0);
  const s = shellMessages[language];

  useEffect(() => { document.documentElement.setAttribute('data-bs-theme', theme); }, [theme]);
  useEffect(() => { document.documentElement.lang = language; }, [language]);
  useEffect(() => { fetchRegistry().then(setRegistry); }, []);

  // Reload module bundles on language change so the switch applies without a reload.
  useEffect(() => {
    Promise.all(open.map((id) => loadModuleBundle(id, language))).then(() => bump((n) => n + 1));
  }, [language, open]);

  const t = useCallback((ns, key) => lookup(ns, language, key), [language]);
  const shell = useMemo(
    () => ({ t, theme, language, getAccessToken }),
    [t, theme, language]
  );

  const toggle = (id) => setOpen((o) => (o.includes(id) ? o.filter((x) => x !== id) : [...o, id]));

  return (
    <ShellProvider value={shell}>
      <nav className="navbar navbar-expand bg-body-tertiary px-3" aria-label={s.launcher}>
        <span className="navbar-brand">SLG OS spike</span>
        <ul className="navbar-nav me-auto" data-testid="launcher">
          {registry.accepted.length === 0 && <li className="nav-item text-body-secondary">{s.noModules}</li>}
          {registry.accepted.map((m) => (
            <li className="nav-item" key={m.id}>
              <button className="btn btn-link nav-link" onClick={() => toggle(m.id)} data-testid={`open-${m.id}`}>
                {s.open}: {m.name[language] ?? m.name.en}
              </button>
            </li>
          ))}
        </ul>
        <div className="d-flex gap-2 align-items-center">
          <span className="navbar-text" data-testid="user">{auth.user.profile.preferred_username}</span>
          <button className="btn btn-sm btn-outline-secondary" data-testid="sign-out" onClick={() => signOut()}>Sign out</button>
          <button className="btn btn-sm btn-outline-secondary" data-testid="toggle-theme" onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
            {s.theme}: {theme === 'light' ? s.light : s.dark}
          </button>
          <button className="btn btn-sm btn-outline-secondary" data-testid="toggle-lang" onClick={() => setLanguage(language === 'en' ? 'de' : 'en')}>
            {s.language}: {language.toUpperCase()}
          </button>
        </div>
      </nav>
      {auth.status === 'expired' && (
        <div className="alert alert-warning m-3" role="alert" data-testid="session-expired">
          Session expired. <button className="btn btn-link p-0" onClick={() => mgr.signinSilent().catch(() => mgr.signinRedirect())}>Sign in again</button>
        </div>
      )}
      <main className="container-fluid py-3">
        {registry.rejected.length > 0 && (
          <div className="alert alert-warning" data-testid="rejected">{registry.rejected.map((r) => `${r.id}: ${r.error}`).join('; ')}</div>
        )}
        {open.map((id) => {
          const m = registry.accepted.find((x) => x.id === id);
          return m ? <ModuleWindow key={id} m={m} shell={shell} s={s} onClose={() => toggle(id)} /> : null;
        })}
      </main>
    </ShellProvider>
  );
}
