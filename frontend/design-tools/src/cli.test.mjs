import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { run } from './cli.mjs';

const repo = new URL('../../../', import.meta.url).pathname;

/** A scratch copy of the design sources and the generated files, with output capture. */
function workspace() {
  const root = mkdtempSync(join(tmpdir(), 'design-tools-'));
  cpSync(join(repo, 'design'), join(root, 'design'), { recursive: true });
  cpSync(join(repo, 'frontend/design-system/src'), join(root, 'pkg/src'), { recursive: true });
  const lines = { out: [], err: [] };
  const env = {
    designDir: join(root, 'design'),
    packageDir: join(root, 'pkg'),
    repoRoot: root,
    platformSourceDirs: [join(root, 'pkg/src')],
    lockFile: join(repo, 'docs/design/assets.lock'),
    packageLockFile: join(repo, 'frontend/package-lock.json'),
    noticesFile: join(repo, 'THIRD-PARTY-NOTICES'),
    out: (l) => lines.out.push(l),
    err: (l) => lines.err.push(l),
  };
  const edit = (file, mutate) => {
    const path = join(root, file);
    writeFileSync(path, JSON.stringify(mutate(JSON.parse(readFileSync(path, 'utf8'))), null, 2));
  };
  return { root, env, lines, edit };
}

describe('design-tools command line', () => {
  it('passes check, contrast and assets on the committed sources', () => {
    const { env, lines } = workspace();
    expect(run(['check'], env)).toBe(0);
    expect(run(['contrast'], env)).toBe(0);
    expect(run(['assets'], env)).toBe(0);
    expect(lines.err).toEqual([]);
    expect(lines.out.join('\n')).toMatch(/tokens-sync passed.*contrast check passed.*assets check passed/s);
  });

  it('prints usage and exits 2 for an unknown command', () => {
    const { env, lines } = workspace();
    expect(run(['frobnicate'], env)).toBe(2);
    expect(run([], env)).toBe(2);
    expect(lines.err[0]).toMatch(/usage/);
  });

  describe('generate and tokens-sync', () => {
    it('regenerates a deleted file and rewrites a stale one', () => {
      const { env, root, lines } = workspace();
      const target = join(root, 'pkg/src/generated/tokens.css');
      writeFileSync(target, '/* stale */');
      expect(run(['check'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/tokens\.css differs from the generated output/);
      expect(run(['generate'], env)).toBe(0);
      expect(run(['check'], env)).toBe(0);
    });

    it('fails when a source changed but the generated files were not regenerated', () => {
      const { env, edit, lines } = workspace();
      edit('design/tokens/space.json', (list) => [...list, { name: 'space-8', px: 64, usage: 'extra step' }]);
      expect(run(['check'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/tokens-sync failed/);
    });

    it('fails when an unknown file sits in the generated directory', () => {
      const { env, root, lines } = workspace();
      writeFileSync(join(root, 'pkg/src/generated/leftover.css'), '');
      expect(run(['check'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/leftover\.css is not generated/);
    });

    it('reports a missing generated file', () => {
      const { env, root, lines } = workspace();
      mkdirSync(join(root, 'empty/src'), { recursive: true });
      expect(run(['check'], { ...env, packageDir: join(root, 'empty') })).toBe(1);
      expect(lines.err.join('\n')).toMatch(/tokens\.css is missing/);
    });

    it('fails when platform code uses a Bootstrap variable that is not mapped', () => {
      const { env, root, lines } = workspace();
      writeFileSync(join(root, 'pkg/src/Unmapped.tsx'), "export const x = 'var(--bs-tooltip-bg)';\n");
      expect(run(['check'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/--bs-tooltip-bg is used but not mapped/);
    });

    it('refuses to generate from invalid sources and writes nothing', () => {
      const { env, edit, root, lines } = workspace();
      edit('design/tokens/color.json', (list) => list.filter((c) => c.name !== 'link'));
      writeFileSync(join(root, 'pkg/src/generated/tokens.css'), '/* keep */');
      expect(run(['generate'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/required token "link" is missing/);
      expect(readFileSync(join(root, 'pkg/src/generated/tokens.css'), 'utf8')).toBe('/* keep */');
    });
  });

  describe('contrast self-test fixture', () => {
    it('fails when a token pair falls below its threshold (a deliberately failing token fixture)', () => {
      const { env, edit, lines } = workspace();
      edit('design/tokens/color.json', (list) => list.map((c) => (c.name === 'ink-muted' ? { ...c, light: '#8c8c8c' } : c)));
      expect(run(['contrast'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/ink-muted-on-raised \[light\]: 3\.\d\d:1, needs 4\.5:1/);
    });

    it('fails when glass would let secondary text fall below 4.5:1 over a backdrop', () => {
      const { env, edit, lines } = workspace();
      edit('design/tokens/color.json', (list) => list.map((c) => (c.name === 'surface-glass' ? { ...c, light: 'rgba(255, 255, 255, 0.2)' } : c)));
      expect(run(['contrast'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/ink-strong-on-glass \[light over black\]/);
    });

    it('fails when a Bootstrap mapped pair no longer passes', () => {
      const { env, edit, lines } = workspace();
      edit('design/bootstrap-map.json', (map) => ({ ...map, entries: map.entries.map((e) => (e.bs === '--bs-secondary-color' ? { ...e, token: 'divider' } : e)) }));
      expect(run(['contrast'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/bs-secondary-text-on-secondary-bg/);
    });

    it('fails on invalid sources without evaluating', () => {
      const { env, edit, lines } = workspace();
      edit('design/pairs.json', (list) => [...list, { id: 'broken', fg: 'ink-strong', bg: 'surface-raised', role: 'banner' }]);
      expect(run(['contrast'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/invalid token sources/);
    });

    it('turns a thrown error into exit code 1', () => {
      const { env, lines } = workspace();
      expect(run(['contrast'], { ...env, designDir: join(env.repoRoot, 'nowhere') })).toBe(1);
      expect(lines.err[0]).toMatch(/^error: cannot read/);
    });
  });

  describe('styles', () => {
    it('builds the stylesheet into the package and reports its size', () => {
      const { env, root, lines } = workspace();
      expect(run(['styles'], env)).toBe(0);
      expect(lines.out.join('\n')).toMatch(/built .*design-system\.css \(\d+\.\d KB gzip\)/);
      expect(existsSync(join(root, 'pkg/dist/design-system.css'))).toBe(true);
    });

    it('fails when the stylesheet contains a data: URL', () => {
      const { env, root, lines } = workspace();
      const scss = join(root, 'pkg/src/styles/index.scss');
      writeFileSync(scss, `${readFileSync(scss, 'utf8').replace('$form-select-indicator: none;', '')}\n`);
      expect(run(['styles'], env)).toBe(1);
      expect(lines.err.join('\n')).toMatch(/data: URL/);
    });
  });

  describe('assets', () => {
    it('fails when the asset lock and the lock file disagree', () => {
      const { env, root, lines } = workspace();
      const lock = JSON.parse(readFileSync(env.lockFile, 'utf8'));
      lock.assets[0].version = '5.3.7';
      const copy = join(root, 'assets.lock');
      writeFileSync(copy, JSON.stringify(lock));
      expect(run(['assets'], { ...env, lockFile: copy })).toBe(1);
      expect(lines.err.join('\n')).toMatch(/lock file has 5\.3\.8, assets\.lock says 5\.3\.7/);
    });
  });
});
