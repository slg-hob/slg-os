import { mkdtempSync, cpSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildStyles, checkBudget, CSS_GZIP_BUDGET_KB, findForbiddenUrls, gzipSize } from './styles.mjs';

const packageSource = new URL('../../design-system/src', import.meta.url).pathname;

describe('buildStyles', () => {
  const dir = mkdtempSync(join(tmpdir(), 'styles-'));
  cpSync(packageSource, join(dir, 'src'), { recursive: true });
  const { css, file, gzipBytes } = buildStyles(dir);

  it('writes dist/design-system.css', () => {
    expect(file).toBe(join(dir, 'dist/design-system.css'));
    expect(readFileSync(file, 'utf8')).toBe(css);
    expect(gzipBytes).toBe(gzipSize(css));
  });

  it('puts the tokens first, then Bootstrap, then the runtime mapping', () => {
    const tokens = css.indexOf('--slg-surface-canvas: #f2f2f2');
    const bootstrap = css.indexOf('.btn{');
    const mapping = css.indexOf('--bs-body-bg: var(--slg-surface-canvas)');
    expect(tokens).toBeGreaterThan(-1);
    expect(bootstrap).toBeGreaterThan(tokens);
    expect(mapping).toBeGreaterThan(bootstrap);
  });

  it('builds Bootstrap from the generated maps: brand primary, token radius and pill buttons', () => {
    expect(css).toContain('--bs-primary: #00aeef');
    expect(css).toMatch(/\.btn\{[^}]*--bs-btn-border-radius:\s*9999px/);
    expect(css).toMatch(/--bs-border-radius:\s*8px/);
  });

  it('sets dark text on primary buttons because white fails 4.5:1 on the brand colour', () => {
    expect(css).toMatch(/\.btn-primary\{[^}]*--bs-btn-color:\s*#262626/);
  });

  it('uses the Source Sans 3 stack from the type tokens', () => {
    expect(css).toMatch(/--bs-font-sans-serif:\s*"Source Sans 3 Variable"/);
  });

  it('stays inside the size budget and free of forbidden URLs', () => {
    expect(checkBudget(css)).toBeUndefined();
    expect(findForbiddenUrls(css)).toEqual([]);
    expect(gzipBytes).toBeLessThan(CSS_GZIP_BUDGET_KB * 1024);
  });
});

describe('findForbiddenUrls', () => {
  it.each([
    ['a data URL', '.a{background:url("data:image/svg+xml,%3csvg")}', /data: URL/],
    ['an unquoted data URL', '.a{background:url(data:image/png;base64,AAAA)}', /data: URL/],
    ['an https URL', '.a{background:url(https://example.org/a.png)}', /network URL/],
    ['a protocol-relative URL', ".a{background:url('//cdn.example.org/a.png')}", /network URL/],
    ['an imported URL', '@import url("https://fonts.example.org/a.css");', /network URL|imports a network URL/],
    ['an import without url()', '@import "https://fonts.example.org/a.css";', /imports a network URL/],
  ])('flags %s', (_label, css, message) => {
    expect(findForbiddenUrls(css).join('\n')).toMatch(message);
  });

  it.each([
    ['a relative file', '.a{background:url("./a.svg")}'],
    ['a fragment reference', '.a{filter:url(#blur)}'],
    ['plain rules', '.a{color:red}'],
  ])('accepts %s', (_label, css) => {
    expect(findForbiddenUrls(css)).toEqual([]);
  });
});

describe('checkBudget', () => {
  it('reports a stylesheet above the budget and accepts one below', () => {
    const noisy = Array.from({ length: 4000 }, (_, i) => `.c${i}{margin:${i}px ${(i * 7919) % 1000}px}`).join('');
    expect(checkBudget(noisy, 1)).toMatch(/KB gzip, budget is 1 KB/);
    expect(checkBudget('.a{color:red}', 1)).toBeUndefined();
  });
});
