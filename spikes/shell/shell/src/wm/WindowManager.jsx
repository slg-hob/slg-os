import './wm.css';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Window from './Window.jsx';
import { KEYS, emptyState, reduce, sanitize } from './store.js';

const fmt = (tpl, vars) => tpl.replace(/\{(\w+)\}/g, (_, k) => vars[k]);
const NARROW_Q = '(max-width: 767.98px)';

export default function WindowManager({ modules, language, s, engine, renderContent, registerGuard, nav, authExtra }) {
  const deskRef = useRef(null);
  const [size, setSize] = useState({ w: 1000, h: 600 });
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW_Q).matches);
  const [state, setState] = useState(emptyState);
  const [confirmFor, setConfirmFor] = useState(null);
  const [message, setMessage] = useState('');
  const [notice, setNotice] = useState('');
  const restored = useRef(false);
  const els = useRef(new Map());
  const openers = useRef(new Map());
  const guards = useRef(new Map());
  const focusReq = useRef(null);
  const taskbarRef = useRef(null);
  const navRef = useRef(null);
  const ctx = useRef({ vw: 1000, vh: 600, modules });
  ctx.current = { vw: size.w, vh: size.h, modules };

  const dispatch = useCallback((a) => setState((st) => reduce(st, a, ctx.current)), []);
  const registerEl = useCallback((id, el) => { if (el) els.current.set(id, el); else els.current.delete(id); }, []);
  const say = useCallback((text) => setMessage(text), []);

  const nameOf = useCallback((w) => {
    const m = modules.find((x) => x.id === w.moduleId);
    const base = m?.name[language] ?? m?.name.en ?? w.moduleId;
    return m?.instanceMode === 'multiInstance' ? `${base} (${w.n})` : base;
  }, [modules, language]);

  // ---- viewport ----
  useEffect(() => {
    const ro = new ResizeObserver(([e]) => setSize({ w: Math.round(e.contentRect.width), h: Math.round(e.contentRect.height) }));
    ro.observe(deskRef.current);
    const mq = window.matchMedia(NARROW_Q);
    const on = () => setNarrow(mq.matches);
    mq.addEventListener('change', on);
    return () => { ro.disconnect(); mq.removeEventListener('change', on); };
  }, []);
  useEffect(() => { if (restored.current) dispatch({ type: 'clamp' }); }, [size.w, size.h, dispatch]);

  // ---- restore (UI-6) and deep link (UI-5) once the registry is known ----
  useEffect(() => {
    if (restored.current || modules.length === 0) return;
    restored.current = true;
    const ids = modules.map((m) => m.id);
    let st = sanitize(localStorage.getItem(KEYS.desktop), ids) ?? emptyState();
    const nar = sanitize({ v: 1, wins: [], order: [] }, ids) && JSON.parse(localStorage.getItem(KEYS.narrow) || 'null');
    if (window.matchMedia(NARROW_Q).matches && nar && st.wins.some((w) => w.id === nar.active)) st = { ...st, active: nar.active };
    // lazy: only active and visible windows load content (ModuleWindow loads on first visibility)
    const dropped = st.dropped || 0;
    delete st.dropped;
    const m = /^\/app\/([^/]+)/.exec(location.pathname);
    if (m) {
      if (ids.includes(m[1])) st = reduce(st, { type: 'open', moduleId: m[1] }, ctx.current);
      else setNotice(s.notAvailable);
    }
    if (dropped > 0) setNotice(s.restoreSkipped);
    setState(st);
    if (st.wins.length > 0) say(fmt(s.announceRestoredN, { n: st.wins.length }));
  }, [modules, s, say]);

  // ---- persistence (debounced) ----
  useEffect(() => {
    if (!restored.current) return undefined;
    const t = setTimeout(() => {
      try {
        localStorage.setItem(KEYS.desktop, JSON.stringify(state));
        localStorage.setItem(KEYS.narrow, JSON.stringify({ v: 1, active: state.active, order: state.order }));
      } catch { /* storage may be unavailable */ }
    }, 250);
    return () => clearTimeout(t);
  }, [state]);

  // ---- URL reflects the active window only ----
  const activeWin = state.wins.find((w) => w.id === state.active && w.mode !== 'min');
  useEffect(() => {
    if (!restored.current) return;
    const path = activeWin ? `/app/${activeWin.moduleId}` : '/';
    if (location.pathname !== path && (location.pathname.startsWith('/app/') || activeWin)) history.pushState(null, '', path);
  }, [activeWin?.moduleId, activeWin?.id]);
  useEffect(() => {
    const onPop = () => {
      const m = /^\/app\/([^/]+)/.exec(location.pathname);
      const w = m && state.wins.find((x) => x.moduleId === m[1]);
      if (w) dispatch({ type: 'focus', id: w.id });
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [state.wins, dispatch]);

  // ---- focus management (UI-2) ----
  useEffect(() => {
    const req = focusReq.current;
    if (!req) return;
    focusReq.current = null;
    if (req.type === 'window') els.current.get(req.id)?.focus();
    else if (req.type === 'opener') {
      const el = req.el;
      if (el && document.contains(el) && !el.closest('[inert]') && el.offsetParent !== null) el.focus();
      else {
        const top = taskbarRef.current?.querySelector('button[data-task]:not([disabled])');
        (top || navRef.current?.querySelector('button'))?.focus();
      }
    } else if (req.type === 'taskbar') {
      taskbarRef.current?.querySelector(`[data-task="${req.id}"]`)?.focus();
    }
  });

  // ---- actions (each produces exactly one announcement) ----
  const openModule = useCallback((moduleId) => {
    const m = modules.find((x) => x.id === moduleId);
    const existing = state.wins.find((w) => w.moduleId === moduleId);
    const reuse = existing && m?.instanceMode !== 'multiInstance';
    const id = reuse ? existing.id : `${moduleId}-${(state.seq[moduleId] || 0) + 1}`;
    if (!reuse) openers.current.set(id, document.activeElement);
    focusReq.current = { type: 'window', id };
    dispatch({ type: 'open', moduleId });
    const total = reuse ? state.wins.length : state.wins.length + 1;
    const nm = m?.name[language] ?? moduleId;
    say(reuse ? fmt(s.announceActive, { name: nm }) : fmt(s.announceOpened, { name: nm, n: total, total }));
  }, [modules, state.wins, state.seq, dispatch, language, s, say]);

  const focusWindow = useCallback((id) => {
    focusReq.current = { type: 'window', id };
    dispatch({ type: 'focus', id });
    const w = state.wins.find((x) => x.id === id);
    if (w) say(fmt(s.announceActive, { name: nameOf(w) }));
  }, [dispatch, state.wins, nameOf, s, say]);

  const doClose = useCallback((id) => {
    const w = state.wins.find((x) => x.id === id);
    focusReq.current = { type: 'opener', el: openers.current.get(id) };
    guards.current.delete(id);
    dispatch({ type: 'close', id });
    if (w) say(fmt(s.announceClosed, { name: nameOf(w) }));
  }, [state.wins, dispatch, nameOf, s, say]);

  const requestClose = useCallback((id) => {
    if (guards.current.get(id)?.()) setConfirmFor(id);
    else doClose(id);
  }, [doClose]);

  const wmDispatch = useCallback((a) => {
    const w = state.wins.find((x) => x.id === a.id);
    if (a.type === 'minimize' && w) {
      focusReq.current = { type: 'taskbar', id: a.id };
      say(fmt(s.announceMinimized, { name: nameOf(w) }));
    } else if (a.type === 'maximize' && w) say(fmt(s.announceMaximized, { name: nameOf(w) }));
    else if (a.type === 'restore' && w) say(fmt(s.announceRestored, { name: nameOf(w) }));
    else if (a.type === 'snap' && w) say(fmt(s.announceSnapped, { name: nameOf(w) }));
    dispatch(a);
  }, [state.wins, dispatch, nameOf, s, say]);

  // ---- global keyboard (UI-15) ----
  const visible = state.order.filter((id) => state.wins.find((w) => w.id === id)?.mode !== 'min');
  useEffect(() => {
    const onKey = (e) => {
      if (e.ctrlKey && e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && visible.length > 1) {
        e.preventDefault();
        const i = visible.indexOf(state.active);
        const next = visible[(i + (e.key === 'ArrowRight' ? 1 : -1) + visible.length) % visible.length];
        focusWindow(next);
      }
      if (e.key === 'F6') {
        e.preventDefault();
        const regions = [navRef.current?.querySelector('button'), activeWin && els.current.get(activeWin.id), taskbarRef.current?.querySelector('button[data-task]')].filter(Boolean);
        const cur = regions.findIndex((r) => r === document.activeElement || r.contains(document.activeElement));
        regions[(cur + (e.shiftKey ? -1 : 1) + regions.length) % regions.length]?.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  });

  const modalOpen = confirmFor !== null;
  const taskKey = (e) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    const btns = [...taskbarRef.current.querySelectorAll('button[data-task]')];
    const i = btns.indexOf(document.activeElement);
    if (i < 0) return;
    e.preventDefault();
    btns[(i + (e.key === 'ArrowRight' ? 1 : -1) + btns.length) % btns.length].focus();
  };
  const taskClick = (w) => {
    if (w.mode === 'min' || w.id !== state.active) focusWindow(w.id);
    else wmDispatch({ type: 'minimize', id: w.id });
  };

  const guardApi = useMemo(() => ({ set: (id, fn) => (fn ? guards.current.set(id, fn) : guards.current.delete(id)) }), []);
  useEffect(() => { registerGuard?.(guardApi); }, [registerGuard, guardApi]);

  const rovingId = (state.wins.find((w) => w.id === state.active) ?? state.wins[0])?.id;

  return (
    <div className="wm-root">
      <div>
        <button className="btn btn-sm btn-light visually-hidden-focusable" data-testid="skip-taskbar" onClick={() => taskbarRef.current?.querySelector('button')?.focus()}>{s.skipTaskbar}</button>
        <button className="btn btn-sm btn-light visually-hidden-focusable" data-testid="skip-window" onClick={() => activeWin && els.current.get(activeWin.id)?.focus()}>{s.skipWindow}</button>
      </div>
      <div ref={navRef} inert={modalOpen}>{nav({ openModule })}</div>
      {notice && <div className="alert alert-info m-2 py-1" role="status" data-testid="notice">{notice}</div>}
      <div ref={deskRef} className="wm-desk" role={state.wins.length === 0 ? 'main' : undefined} data-testid="desk" data-narrow={narrow ? 'true' : 'false'} data-engine={engine}>
        {state.wins.length === 0 && <p className="p-3 text-body-secondary">{s.emptyDesk}</p>}
        {state.order.map((id) => {
          const w = state.wins.find((x) => x.id === id);
          const m = modules.find((x) => x.id === w.moduleId);
          if (!m) return null;
          const active = id === state.active && w.mode !== 'min';
          return (
            <Window
              key={id}
              win={w}
              title={nameOf(w)}
              s={s}
              active={active}
              narrow={narrow}
              engine={engine}
              deskSize={size}
              dispatch={wmDispatch}
              modalOpen={modalOpen}
              isModalOwner={confirmFor === id}
              onRequestClose={requestClose}
              registerEl={registerEl}
              dialog={confirmFor === id && (
                <ConfirmDialog s={s} onStay={() => { setConfirmFor(null); focusReq.current = { type: 'window', id }; }} onLeave={() => { setConfirmFor(null); doClose(id); }} />
              )}
            >
              {renderContent(w, m, w.mode !== 'min' && (!narrow || active))}
            </Window>
          );
        })}
      </div>
      <nav ref={taskbarRef} className="wm-taskbar border-top bg-body-tertiary px-2 d-flex align-items-center" aria-label={s.taskbar} inert={modalOpen} onKeyDown={taskKey}>
        <ul className="list-unstyled d-flex gap-1 m-0 flex-wrap" aria-label={s.openWindows}>
          {state.wins.map((w) => (
            <li key={w.id}>
              <button
                data-task={w.id}
                data-testid={`task-${w.id}`}
                tabIndex={w.id === rovingId ? 0 : -1}
                aria-current={w.id === state.active && w.mode !== 'min' ? 'true' : undefined}
                className={`btn btn-sm ${w.id === state.active && w.mode !== 'min' ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => taskClick(w)}
              >
                {nameOf(w)}{w.mode === 'min' && <span className="visually-hidden"> ({s.minimized})</span>}
              </button>
            </li>
          ))}
        </ul>
        {authExtra}
      </nav>
      <div role="status" aria-live="polite" className="visually-hidden" data-testid="live">{message}</div>
    </div>
  );
}

function ConfirmDialog({ s, onStay, onLeave }) {
  const ref = useRef(null);
  useEffect(() => { ref.current?.querySelector('button')?.focus(); }, []);
  const onKey = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onStay(); }
    if (e.key === 'Tab') {
      const b = [...ref.current.querySelectorAll('button')];
      const i = b.indexOf(document.activeElement);
      e.preventDefault();
      b[(i + (e.shiftKey ? -1 : 1) + b.length) % b.length].focus();
    }
  };
  return (
    <div className="wm-dialog" onKeyDown={onKey}>
      <div ref={ref} role="alertdialog" aria-modal="true" aria-labelledby="cd-title" aria-describedby="cd-text" className="card p-3 shadow" data-testid="confirm-dialog">
        <h2 id="cd-title" className="h6">{s.confirmCloseTitle}</h2>
        <p id="cd-text">{s.confirmCloseText}</p>
        <div className="d-flex gap-2 justify-content-end">
          <button className="btn btn-sm btn-primary" data-testid="confirm-stay" onClick={onStay}>{s.stay}</button>
          <button className="btn btn-sm btn-outline-danger" data-testid="confirm-leave" onClick={onLeave}>{s.closeAnyway}</button>
        </div>
      </div>
    </div>
  );
}
