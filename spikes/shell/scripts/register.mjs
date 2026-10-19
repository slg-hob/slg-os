// "Registration" step of the spike (WP1.5b): copies each remote build into an immutable store,
// hashes every JS file (SHA-384) and writes the generated registry with integrity data.
// Usage: node scripts/register.mjs
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, symlinkSync, unlinkSync, writeFileSync, lstatSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const dirs = { a: 'remote-a', b: 'remote-b', mismatch: 'remote-mismatch', evil: 'remote-evil' };
const manifest = JSON.parse(readFileSync(join(root, 'registry/modules.json'), 'utf8'));

const walk = (d) => readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)]));
const sri = (buf) => `sha384-${createHash('sha384').update(buf).digest('base64')}`;

const out = { modules: [] };
for (const m of manifest.modules) {
  const dist = join(root, dirs[m.id], 'dist');
  const store = join(root, '.run/store', m.id);
  const hashOfDist = createHash('sha256');
  for (const f of walk(dist).sort()) hashOfDist.update(readFileSync(f));
  const content = hashOfDist.digest('hex').slice(0, 12);
  const target = join(store, `${m.version}-${content}`); // content-addressed directory
  mkdirSync(store, { recursive: true });
  if (!existsSync(target)) cpSync(dist, target, { recursive: true });
  const link = join(store, 'current');
  try { if (lstatSync(link)) unlinkSync(link); } catch {}
  symlinkSync(target, link);
  const integrity = {};
  for (const f of walk(target)) {
    if (f.endsWith('.js')) integrity[`/modules/${m.id}/remote/${relative(target, f)}`] = sri(readFileSync(f));
  }
  out.modules.push({ ...m, content, integrity });
}
mkdirSync(join(root, '.run/registry'), { recursive: true });
writeFileSync(join(root, '.run/registry/modules.json'), JSON.stringify(out, null, 2));
console.log(`registered ${out.modules.length} modules; files hashed: ${out.modules.map((m) => `${m.id}=${Object.keys(m.integrity).length}`).join(', ')}`);
