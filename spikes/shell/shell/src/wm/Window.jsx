import { useEffect, useRef, useState } from 'react';
import { Rnd } from 'react-rnd';
import { MIN_W, MIN_H } from './store.js';

const STEP = 16;

// One window. Semantics (WP2.3): labelled section, title is the window's h1, the active window is the main landmark,
// hidden/minimized windows are display:none and inert, DOM order equals stack order equals tab order.
export default function Window({ win, title, s, active, narrow, engine, deskSize, dispatch, modalOpen, isModalOwner, onRequestClose, children, registerEl, dialog }) {
  const ref = useRef(null);
  const [menu, setMenu] = useState(false);
  const [kbd, setKbd] = useState(null); // { op: 'move'|'resize', orig }
  const menuBtn = useRef(null);
  const hidden = win.mode === 'min' || (narrow && !active);
  const maximized = narrow || win.mode === 'max';
  const inert = hidden || (modalOpen && !isModalOwner);

  useEffect(() => { registerEl(win.id, ref.current); return () => registerEl(win.id, null); }, [win.id, registerEl]);

  const geo = maximized ? { x: 0, y: 0, w: deskSize.w, h: deskSize.h } : { x: win.x, y: win.y, w: win.w, h: win.h };

  // ---- in-house pointer engine (move/resize/snap) ----
  const drag = useRef(null);
  const onTitlePointerDown = (e) => {
    if (engine !== 'inhouse' || narrow || e.target.closest('button')) return;
    if (win.mode === 'max') return;
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { kind: 'move', sx: e.clientX, sy: e.clientY, ox: win.x, oy: win.y };
    dispatch({ type: 'focus', id: win.id });
  };
  const onHandleDown = (dir) => (e) => {
    if (engine !== 'inhouse' || narrow || win.mode === 'max') return;
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { kind: 'resize', dir, sx: e.clientX, sy: e.clientY, o: { x: win.x, y: win.y, w: win.w, h: win.h } };
    dispatch({ type: 'focus', id: win.id });
  };
  const onPointerMove = (e) => {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.sx;
    const dy = e.clientY - d.sy;
    if (d.kind === 'move') return dispatch({ type: 'move', id: win.id, x: d.ox + dx, y: d.oy + dy });
    let { x, y, w, h } = d.o;
    if (d.dir.includes('e')) w = d.o.w + dx;
    if (d.dir.includes('s')) h = d.o.h + dy;
    if (d.dir.includes('w')) { w = d.o.w - dx; x = d.o.x + dx; }
    if (d.dir.includes('n')) { h = d.o.h - dy; y = d.o.y + dy; }
    if (w < MIN_W) { if (d.dir.includes('w')) x -= MIN_W - w; w = MIN_W; }
    if (h < MIN_H) { if (d.dir.includes('n')) y -= MIN_H - h; h = MIN_H; }
    dispatch({ type: 'resize', id: win.id, x, y, w, h });
  };
  const onPointerUp = (e) => {
    const d = drag.current;
    drag.current = null;
    if (!d || d.kind !== 'move') return;
    const rect = ref.current?.parentElement?.getBoundingClientRect();
    if (!rect) return;
    if (e.clientY - rect.top <= 8) dispatch({ type: 'snap', id: win.id, side: 'max' });
    else if (e.clientX - rect.left <= 8) dispatch({ type: 'snap', id: win.id, side: 'left' });
    else if (rect.right - e.clientX <= 8) dispatch({ type: 'snap', id: win.id, side: 'right' });
  };

  // ---- keyboard: window menu and keyboard move/resize (WCAG 2.1.1, 2.5.7) ----
  const items = narrow
    ? [{ k: 'close', label: s.closeWindow }]
    : [
        { k: 'move', label: s.move }, { k: 'resize', label: s.resize },
        { k: 'snapLeft', label: s.snapLeft }, { k: 'snapRight', label: s.snapRight },
        { k: win.mode === 'max' ? 'restore' : 'maximize', label: win.mode === 'max' ? s.restore : s.maximize },
        { k: 'minimize', label: s.minimize }, { k: 'close', label: s.closeWindow },
      ];
  const run = (k) => {
    setMenu(false);
    menuBtn.current?.focus();
    if (k === 'move' || k === 'resize') { setKbd({ op: k, orig: { x: win.x, y: win.y, w: win.w, h: win.h } }); return; }
    if (k === 'snapLeft') return dispatch({ type: 'snap', id: win.id, side: 'left' });
    if (k === 'snapRight') return dispatch({ type: 'snap', id: win.id, side: 'right' });
    if (k === 'maximize') return dispatch({ type: 'maximize', id: win.id });
    if (k === 'restore') return dispatch({ type: 'restore', id: win.id });
    if (k === 'minimize') return dispatch({ type: 'minimize', id: win.id });
    if (k === 'close') return onRequestClose(win.id);
  };
  const onKeyDown = (e) => {
    if (e.altKey && e.code === 'Space') { e.preventDefault(); setMenu(true); return; }
    if (kbd) {
      const move = kbd.op === 'move';
      const d = { ArrowLeft: [-STEP, 0], ArrowRight: [STEP, 0], ArrowUp: [0, -STEP], ArrowDown: [0, STEP] }[e.key];
      if (d) {
        e.preventDefault();
        if (move) dispatch({ type: 'move', id: win.id, x: win.x + d[0], y: win.y + d[1] });
        else dispatch({ type: 'resize', id: win.id, x: win.x, y: win.y, w: win.w + d[0], h: win.h + d[1] });
      } else if (e.key === 'Enter') { e.preventDefault(); setKbd(null); }
      else if (e.key === 'Escape') { e.preventDefault(); dispatch({ type: move ? 'move' : 'resize', id: win.id, ...kbd.orig }); setKbd(null); }
    } else if (e.key === 'Escape' && menu) { e.preventDefault(); setMenu(false); menuBtn.current?.focus(); }
  };
  const onMenuKey = (e) => {
    const btns = [...e.currentTarget.querySelectorAll('[role=menuitem]')];
    const i = btns.indexOf(document.activeElement);
    if (e.key === 'ArrowDown') { e.preventDefault(); btns[(i + 1) % btns.length].focus(); }
    if (e.key === 'ArrowUp') { e.preventDefault(); btns[(i - 1 + btns.length) % btns.length].focus(); }
    if (e.key === 'Home') { e.preventDefault(); btns[0].focus(); }
    if (e.key === 'End') { e.preventDefault(); btns.at(-1).focus(); }
  };
  useEffect(() => { if (menu) ref.current?.querySelector('[role=menuitem]')?.focus(); }, [menu]);

  const controls = (
    <>
      {!narrow && (
        <>
          <button ref={menuBtn} className="btn btn-sm btn-outline-secondary btn-quiet wm-btn" aria-haspopup="menu" aria-expanded={menu} data-testid={`menu-${win.id}`} onClick={() => setMenu((m) => !m)}>
            <span aria-hidden="true">⋯</span><span className="visually-hidden">{s.windowMenu}</span>
          </button>
          <button className="btn btn-sm btn-outline-secondary btn-quiet wm-btn" data-testid={`minimize-${win.id}`} onClick={() => dispatch({ type: 'minimize', id: win.id })}>
            <span aria-hidden="true">–</span><span className="visually-hidden">{s.minimize}</span>
          </button>
          <button className="btn btn-sm btn-outline-secondary btn-quiet wm-btn" data-testid={`maximize-${win.id}`} onClick={() => dispatch({ type: win.mode === 'max' ? 'restore' : 'maximize', id: win.id })}>
            <span aria-hidden="true">{win.mode === 'max' ? '❐' : '□'}</span><span className="visually-hidden">{win.mode === 'max' ? s.restore : s.maximize}</span>
          </button>
        </>
      )}
      <button className="btn btn-sm btn-outline-secondary btn-quiet wm-btn" data-testid={`close-${win.id}`} onClick={() => onRequestClose(win.id)}>
        <span aria-hidden="true">×</span><span className="visually-hidden">{s.closeWindow}</span>
      </button>
    </>
  );

  const role = active ? 'main' : undefined;
  const inner = (
    <section
      ref={ref}
      role={role}
      tabIndex={-1}
      hidden={hidden}
      inert={inert}
      aria-labelledby={`title-${win.id}`}
      data-testid={`window-${win.moduleId}`}
      data-window-id={win.id}
      data-active={active ? 'true' : 'false'}
      className={`wm-win card shadow ${active ? 'wm-active' : ''}`}
      style={engine === 'rnd' ? { position: 'absolute', inset: 0 } : { position: 'absolute', left: geo.x, top: geo.y, width: geo.w, height: geo.h }}
      onKeyDown={onKeyDown}
      onFocus={() => { if (!active && !hidden && !inert) dispatch({ type: 'focus', id: win.id }); }} /* focus must never stay in an obscured window (WCAG 2.4.11) */
      onPointerDown={() => !active && dispatch({ type: 'focus', id: win.id })}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
    >
      <header className="wm-titlebar card-header d-flex align-items-center gap-1 py-1" onPointerDown={onTitlePointerDown}>
        <h1 className="h6 mb-0 flex-grow-1 text-truncate" id={`title-${win.id}`}>{title}</h1>
        {controls}
      </header>
      {menu && (
        <ul role="menu" aria-label={s.windowMenu} className="wm-menu list-unstyled border rounded shadow-sm bg-body p-1 m-0" onKeyDown={onMenuKey} data-testid={`menulist-${win.id}`}>
          {items.map((it) => (
            <li key={it.k} role="none"><button role="menuitem" className="dropdown-item" onClick={() => run(it.k)} data-testid={`mi-${it.k}`}>{it.label}</button></li>
          ))}
        </ul>
      )}
      {kbd && <div className="visually-hidden" role="status">{kbd.op === 'move' ? s.kbdMove : s.kbdResize}</div>}
      <div className="wm-body card-body p-0">{children}</div>
      {dialog}
      {engine === 'inhouse' && !narrow && win.mode !== 'max' && !hidden &&
        ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'].map((d) => (
          <div key={d} className={`wm-handle wm-handle-${d}`} data-handle={d} aria-hidden="true" onPointerDown={onHandleDown(d)} />
        ))}
    </section>
  );

  if (engine !== 'rnd') return inner;
  // react-rnd candidate: stays mounted in narrow mode (cannot be unmounted without remounting the module).
  return (
    <Rnd
      style={{ display: hidden ? 'none' : undefined }}
      position={{ x: geo.x, y: geo.y }}
      size={{ width: geo.w, height: geo.h }}
      minWidth={MIN_W}
      minHeight={MIN_H}
      dragHandleClassName="wm-titlebar"
      cancel="button"
      disableDragging={narrow || win.mode === 'max'}
      enableResizing={!(narrow || win.mode === 'max')}
      onDragStart={() => dispatch({ type: 'focus', id: win.id })}
      onDragStop={(e, d) => dispatch({ type: 'move', id: win.id, x: d.x, y: d.y })}
      onResizeStop={(e, dir, el, delta, pos) => dispatch({ type: 'resize', id: win.id, x: pos.x, y: pos.y, w: el.offsetWidth, h: el.offsetHeight })}
    >
      {inner}
    </Rnd>
  );
}
