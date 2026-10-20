import { readFileSync } from 'node:fs';
import { assertPairsPass, generateAll, GENERATED_HEADER } from './generate.mjs';
import { loadSources } from './tokens.mjs';

const golden = new URL('../test-fixtures/golden/', import.meta.url);
const designDir = new URL('../../../design', import.meta.url).pathname;
const generatedDir = new URL('../../design-system/', import.meta.url).pathname;
const read = (name) => readFileSync(new URL(name, golden), 'utf8');

describe('generator golden output', () => {
  const sources = JSON.parse(read('sources.json'));
  const files = generateAll(sources);

  it.each([
    ['src/generated/tokens.css', 'tokens.css'],
    ['src/generated/bootstrap-map.css', 'bootstrap-map.css'],
    ['src/generated/_tokens.scss', '_tokens.scss'],
    ['src/generated/tokens.ts', 'tokens.ts'],
    ['src/generated/pairs.json', 'pairs.json'],
  ])('writes %s byte-equal to the golden file', (path, golden) => {
    expect(files.get(path)).toBe(read(golden));
  });

  it('is deterministic', () => {
    expect([...generateAll(sources)]).toEqual([...files]);
  });

  it('starts every generated file with the do-not-edit header', () => {
    for (const [path, content] of files) {
      if (path.endsWith('.json')) continue; // JSON cannot carry a comment; the directory is verified by tokens-sync
      expect(content.startsWith(`/* ${GENERATED_HEADER} */`) || content.startsWith(`// ${GENERATED_HEADER}`)).toBe(true);
    }
  });

  it('emits channel triplets only for opaque colours', () => {
    const css = files.get('src/generated/tokens.css');
    expect(css).toContain('--slg-paper-rgb: 255, 255, 255;');
    expect(css).not.toContain('--slg-pane-rgb');
  });

  it('keeps the high-contrast overrides out of the themes and inside the media query', () => {
    const css = files.get('src/generated/tokens.css');
    expect(css.indexOf('--slg-ink: #000000')).toBeGreaterThan(css.indexOf('@media (prefers-contrast: more)'));
  });
});

describe('the committed generated files', () => {
  const { sources } = loadSources(designDir);

  it.each([...generateAll(sources)])('%s equals the generator output', (path, content) => {
    expect(readFileSync(`${generatedDir}${path}`, 'utf8')).toBe(content);
  });

  it('define the selectors of the four theme situations', () => {
    const css = generateAll(sources).get('src/generated/tokens.css');
    expect(css).toContain('[data-bs-theme="dark"] {');
    expect(css).toContain('@media (prefers-contrast: more)');
    expect(css).toContain('@media (forced-colors: active)');
    expect(css).toContain('--slg-focus-ring: Highlight;');
    expect(css).toContain('--slg-shadow-2: none;');
  });

  it('map the Bootstrap variables to tokens only', () => {
    const css = generateAll(sources).get('src/generated/bootstrap-map.css');
    const declarations = css.split('\n').filter((l) => l.includes('--bs-') && l.includes(':'));
    expect(declarations.length).toBeGreaterThan(30);
    for (const line of declarations) expect(line).toMatch(/^ {2}--bs-[a-z0-9-]+: var\(--slg-[a-z0-9-]+\);$/);
  });
});

describe('assertPairsPass', () => {
  it('accepts the committed sources', () => {
    expect(() => assertPairsPass(loadSources(designDir).sources)).not.toThrow();
  });

  it('throws when a pair is below its threshold', () => {
    const { sources } = structuredClone(loadSources(designDir));
    sources.color.find((c) => c.name === 'ink-muted').light = '#aaaaaa';
    expect(() => assertPairsPass(sources)).toThrow(/below threshold/);
  });
});
