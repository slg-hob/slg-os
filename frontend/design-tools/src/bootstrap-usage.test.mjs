import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { findUnmappedBootstrapVariables, readSources } from './bootstrap-usage.mjs';

describe('findUnmappedBootstrapVariables', () => {
  const mapped = new Set(['--bs-body-bg', '--bs-body-color']);

  it('accepts files that use only mapped variables', () => {
    const files = [{ path: 'a.css', text: '.a { color: var(--bs-body-color); background: var(--bs-body-bg); }' }];
    expect(findUnmappedBootstrapVariables(files, mapped)).toEqual([]);
  });

  it('names the file and the variable once even when it is used twice', () => {
    const files = [{ path: 'a.tsx', text: "x('--bs-tooltip-bg'); y('--bs-tooltip-bg'); z('--bs-body-bg')" }];
    expect(findUnmappedBootstrapVariables(files, mapped)).toEqual([
      'a.tsx: --bs-tooltip-bg is used but not mapped to a --slg-* token in design/bootstrap-map.json',
    ]);
  });

  it('ignores files without Bootstrap variables', () => {
    expect(findUnmappedBootstrapVariables([{ path: 'a.ts', text: 'const a = 1;' }], mapped)).toEqual([]);
  });
});

describe('readSources', () => {
  const dir = mkdtempSync(join(tmpdir(), 'sources-'));
  mkdirSync(join(dir, 'nested'));
  mkdirSync(join(dir, 'generated'));
  mkdirSync(join(dir, 'node_modules'));
  writeFileSync(join(dir, 'a.css'), 'a');
  writeFileSync(join(dir, 'a.test.ts'), 'test');
  writeFileSync(join(dir, 'image.png'), 'png');
  writeFileSync(join(dir, 'nested/b.tsx'), 'b');
  writeFileSync(join(dir, 'generated/c.css'), 'c');
  writeFileSync(join(dir, 'node_modules/d.js'), 'd');

  it('reads source files recursively and skips tests, generated files, dependencies and other file types', () => {
    expect(readSources(dir).map((f) => f.path.slice(dir.length + 1)).sort()).toEqual(['a.css', 'nested/b.tsx']);
  });

  it('returns nothing for a directory that does not exist', () => {
    expect(readSources(join(dir, 'missing'))).toEqual([]);
  });
});
