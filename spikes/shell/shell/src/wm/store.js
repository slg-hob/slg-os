// Window state store (shell-owned, serialisable, versioned) - PRD-0002 WP2.5. Pure functions, no DOM access.
export const SCHEMA = 1;
export const MIN_W = 480;
export const MIN_H = 320;
const KEEP = 80; // title bar must stay reachable (UI-3)

export const emptyState = () => ({ v: SCHEMA, wins: [], order: [], active: null, seq: {} });

export function clampGeo(g, vw, vh) {
  const w = Math.max(MIN_W, Math.min(g.w, Math.max(vw, MIN_W)));
  const h = Math.max(MIN_H, Math.min(g.h, Math.max(vh, MIN_H)));
  const x = Math.min(Math.max(g.x, KEEP - w), Math.max(vw - KEEP, 0));
  const y = Math.min(Math.max(g.y, 0), Math.max(vh - 40, 0));
  return { x, y, w, h };
}

const withOrder = (s, id) => ({ ...s, order: [...s.order.filter((x) => x !== id), id], active: id });
const patchWin = (s, id, p) => ({ ...s, wins: s.wins.map((w) => (w.id === id ? { ...w, ...p } : w)) });

export function reduce(state, a, ctx) {
  const { vw, vh } = ctx;
  switch (a.type) {
    case 'open': {
      const mod = ctx.modules.find((m) => m.id === a.moduleId);
      if (!mod) return state;
      const existing = state.wins.find((w) => w.moduleId === a.moduleId);
      if (existing && mod.instanceMode !== 'multiInstance') {
        return reduce(state, { type: 'focus', id: existing.id }, ctx);
      }
      const n = (state.seq[a.moduleId] || 0) + 1;
      const k = state.wins.length % 8;
      const geo = clampGeo({ x: 40 + 30 * k, y: 24 + 30 * k, w: 640, h: 420 }, vw, vh);
      const win = { id: `${a.moduleId}-${n}`, moduleId: a.moduleId, n, ...geo, mode: 'normal' };
      return withOrder({ ...state, wins: [...state.wins, win], seq: { ...state.seq, [a.moduleId]: n } }, win.id);
    }
    case 'close': {
      const order = state.order.filter((x) => x !== a.id);
      return { ...state, wins: state.wins.filter((w) => w.id !== a.id), order, active: state.active === a.id ? (order.filter((id) => state.wins.find((w) => w.id === id)?.mode !== 'min').at(-1) ?? null) : state.active };
    }
    case 'focus': {
      const w = state.wins.find((x) => x.id === a.id);
      if (!w) return state;
      return withOrder(w.mode === 'min' ? patchWin(state, a.id, { mode: w.prevMode || 'normal' }) : state, a.id);
    }
    case 'minimize': {
      const w = state.wins.find((x) => x.id === a.id);
      if (!w || w.mode === 'min') return state;
      const next = patchWin(state, a.id, { mode: 'min', prevMode: w.mode });
      const visible = next.order.filter((id) => next.wins.find((x) => x.id === id).mode !== 'min');
      return { ...next, active: visible.at(-1) ?? null };
    }
    case 'maximize':
      return withOrder(patchWin(state, a.id, { mode: 'max' }), a.id);
    case 'restore':
      return withOrder(patchWin(state, a.id, { mode: 'normal' }), a.id);
    case 'move': {
      const w = state.wins.find((x) => x.id === a.id);
      if (!w) return state;
      return patchWin(state, a.id, { ...clampGeo({ ...w, x: a.x, y: a.y }, vw, vh), mode: 'normal' });
    }
    case 'resize': {
      const w = state.wins.find((x) => x.id === a.id);
      if (!w) return state;
      return patchWin(state, a.id, { ...clampGeo({ x: a.x ?? w.x, y: a.y ?? w.y, w: a.w, h: a.h }, vw, vh), mode: 'normal' });
    }
    case 'snap': {
      if (a.side === 'max') return withOrder(patchWin(state, a.id, { mode: 'max' }), a.id);
      const half = Math.floor(vw / 2);
      const geo = a.side === 'left' ? { x: 0, y: 0, w: half, h: vh } : { x: vw - half, y: 0, w: half, h: vh };
      return withOrder(patchWin(state, a.id, { ...geo, w: Math.max(geo.w, 0), mode: 'snap' }), a.id);
    }
    case 'clamp':
      return { ...state, wins: state.wins.map((w) => ({ ...w, ...clampGeo(w, vw, vh) })) };
    case 'set':
      return a.state;
    default:
      return state;
  }
}

// Persistence with validation (WP2.5: corrupted or old schema must not crash).
export function sanitize(raw, knownModuleIds) {
  try {
    const s = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!s || s.v !== SCHEMA || !Array.isArray(s.wins) || !Array.isArray(s.order)) return null;
    const num = (n) => typeof n === 'number' && Number.isFinite(n);
    const wins = s.wins.filter((w) => w && typeof w.id === 'string' && knownModuleIds.includes(w.moduleId) && [w.x, w.y, w.w, w.h].every(num));
    const ids = new Set(wins.map((w) => w.id));
    const order = s.order.filter((id) => ids.has(id));
    for (const w of wins) if (!order.includes(w.id)) order.push(w.id);
    const mode = (m) => (['normal', 'min', 'max', 'snap'].includes(m) ? m : 'normal');
    return {
      v: SCHEMA,
      wins: wins.map((w) => ({ ...w, mode: mode(w.mode), n: Number.isInteger(w.n) ? w.n : 1 })),
      order,
      active: ids.has(s.active) ? s.active : (order.at(-1) ?? null),
      seq: s.seq && typeof s.seq === 'object' ? s.seq : {},
      dropped: s.wins.length - wins.length,
    };
  } catch {
    return null;
  }
}

export const KEYS = { desktop: 'spike.wm.desktop', narrow: 'spike.wm.narrow' };
