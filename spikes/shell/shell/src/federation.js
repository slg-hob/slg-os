import { registerRemotes, loadRemote } from '@module-federation/runtime';

const MODULE_PREFIX = '/modules/';

// WP1.2: the entry URL from registry data must be a relative path under /modules/<id>/remote/.
export function validateEntry(m) {
  const expected = `${MODULE_PREFIX}${m.id}/remote/`;
  const e = String(m.entry);
  if (!/^[a-z][a-z0-9-]{2,31}$/.test(m.id) && !/^[a-z]$/.test(m.id)) throw new Error(`invalid module id: ${m.id}`);
  if (!e.startsWith(expected) || e.includes('..') || e.includes('//') || /%2e|%2f/i.test(e) || e.includes(':')) {
    throw new Error(`rejected entry URL for ${m.id}: ${e}`);
  }
  return e;
}

export async function fetchRegistry() {
  const res = await fetch('/registry/modules.json', { cache: 'no-store' });
  if (!res.ok) throw new Error(`registry ${res.status}`);
  const { modules } = await res.json();
  const accepted = [];
  const rejected = [];
  for (const m of modules) {
    try {
      validateEntry(m);
      accepted.push(m);
    } catch (e) {
      rejected.push({ id: m.id, error: String(e.message) });
    }
  }
  return { accepted, rejected };
}

const registered = new Set();

export function registerModule(m) {
  if (registered.has(m.scope)) return;
  registerRemotes([{ name: m.scope, entry: validateEntry(m), type: 'module' }]);
  registered.add(m.scope);
}

export async function loadModuleComponent(m) {
  registerModule(m);
  const mod = await loadRemote(`${m.scope}/App`);
  if (!mod?.default) throw new Error(`remote ${m.scope} has no default export`);
  return mod.default;
}
