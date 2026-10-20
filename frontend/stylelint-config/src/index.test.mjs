import stylelint from 'stylelint';
import { ignoreFiles, moduleConfig, platformConfig } from './index.mjs';

/** Lints CSS and returns the warning texts. */
async function warnings(code, config = moduleConfig) {
  const { results } = await stylelint.lint({ code, config, codeFilename: 'module.css' });
  return results.flatMap((r) => r.warnings.map((w) => w.text));
}

describe('module stylelint config', () => {
  it('accepts a module stylesheet that uses tokens and class selectors', async () => {
    const css = `
      .notes { color: var(--slg-ink-strong); background: var(--slg-surface-raised); padding: var(--slg-space-2); border-radius: var(--slg-radius-md); }
      .notes p { margin: 0; }
      .notes > a:hover { text-decoration: underline; }
      .notes__icon { background: transparent; border-color: currentcolor; }`;
    expect(await warnings(css)).toEqual([]);
  });

  it.each([
    ['hex colour', '.a { color: #fff; }', /Raw colours/],
    ['named colour', '.a { color: red; }', /Named colours/],
    ['rgb function', '.a { color: rgb(0 0 0); }', /Colour functions/],
    ['hsl function', '.a { color: hsl(0 0% 0%); }', /Colour functions/],
    ['oklch function', '.a { color: oklch(0.5 0.1 200); }', /Colour functions/],
    ['important', '.a { color: var(--slg-ink-strong) !important; }', /important/],
    ['bootstrap variable use', '.a { color: var(--bs-body-color); }', /--slg-\*/],
    ['bootstrap variable override', '.a { --bs-body-bg: var(--slg-surface-raised); }', /--bs-\*/],
    ['universal selector', '* { margin: 0; }', /Global selectors/],
    ['html selector', 'html { font-size: 12px; }', /Global selectors/],
    ['body selector', 'body { margin: 0; }', /Global selectors/],
    ['root selector', ':root { --x: 1; }', /Global selectors/],
    ['bare element selector', 'button { margin: 0; }', /Bare element/],
    ['shell dialog class', '.slg-shell-dialog { margin: 0; }', /shell classes/],
    ['glass class', '.slg-glass { margin: 0; }', /glass classes/],
    ['data URL', ".a { background: url('data:image/png;base64,AAAA'); }", /data: URLs/],
    ['external URL', '.a { background: url(https://example.org/x.png); }', /network requests/],
    ['import', '@import "other.css";', /@import/],
  ])('rejects %s', async (_label, css, message) => {
    const texts = await warnings(css);
    expect(texts.length).toBeGreaterThan(0);
    expect(texts.join('\n')).toMatch(message);
  });

  it('does not treat names that merely start like a shell class as one', async () => {
    expect(await warnings('.slg-glassy { margin: 0; }')).toEqual([]);
  });
});

describe('platform stylelint config', () => {
  it('allows declaring Bootstrap variables and styling the document', async () => {
    const css = ':root { --bs-body-bg: var(--slg-surface-canvas); } body { margin: 0; }';
    expect(await warnings(css, platformConfig)).toEqual([]);
  });

  it('still rejects raw colours and important', async () => {
    const texts = await warnings('.a { color: #000 !important; }', platformConfig);
    expect(texts.join('\n')).toMatch(/Raw colours/);
    expect(texts.join('\n')).toMatch(/important/);
  });

  it('exempts generated files', () => {
    expect(ignoreFiles).toContain('**/generated/**');
  });
});
