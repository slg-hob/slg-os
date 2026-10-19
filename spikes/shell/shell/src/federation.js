import { registerRemotes, loadRemote } from '@module-federation/runtime';
import { version as reactVersion } from 'react';
import semver from 'semver';

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

// WP1.4: compare the manifest's declared requirements with what the shell provides, at registration time.
export function checkRequires(m) {
  const need = m.requires?.react;
  if (need && !semver.satisfies(reactVersion, need)) {
    throw new Error(`incompatible: module needs react ${need}, shell provides ${reactVersion}`);
  }
}

const skipRequires = new URLSearchParams(location.search).has('skipRequires'); // spike-only switch to observe runtime behaviour

export async function fetchRegistry() {
  const res = await fetch('/registry/modules.json', { cache: 'no-store' });
  if (!res.ok) throw new Error(`registry ${res.status}`);
  const { modules } = await res.json();
  const accepted = [];
  const rejected = [];
  for (const m of modules) {
    try {
      validateEntry(m);
      if (!skipRequires) checkRequires(m);
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
