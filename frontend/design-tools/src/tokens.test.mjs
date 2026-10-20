import { mkdtempSync, writeFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { allPairs, bootstrapTokenPairs, colorMap, loadSources, REQUIRED_COLORS, TOKEN_FILES, validateSources } from './tokens.mjs';

const designDir = new URL('../../../design', import.meta.url).pathname;
const fresh = () => structuredClone(loadSources(designDir));
const problemsFor = (mutate) => {
  const model = fresh();
  mutate(model.sources);
  return validateSources(model);
};

describe('the committed token sources', () => {
  it('are valid', () => {
    expect(validateSources(loadSources(designDir))).toEqual([]);
  });

  it('contain every required colour token of VL-2 AC1', () => {
    const names = new Set(fresh().sources.color.map((c) => c.name));
    for (const required of REQUIRED_COLORS) expect(names.has(required)).toBe(true);
  });

  it('define the spacing, radius and type scales of VL-3 and VL-4', () => {
    const { sources } = fresh();
    expect(sources.space.map((s) => s.px)).toEqual([4, 8, 12, 16, 24, 32, 48]);
    expect(Object.fromEntries(sources.radius.map((r) => [r.name, r.px]))).toEqual({
      'radius-sm': 4, 'radius-md': 8, 'radius-lg': 12, 'radius-xl': 16, 'radius-2xl': 20, 'radius-pill': 9999,
    });
    const scale = Object.fromEntries(sources.type.filter((t) => t.kind === 'scale').map((t) => [t.name, `${t.size}/${t.lineHeight} ${t.weight}`]));
    expect(scale).toMatchObject({ display: '32/44 300', 'page-title': '24/33 300', section: '20/28 300', body: '16/24 400', table: '14/20 400', label: '13/16 600', caption: '13/18 400' });
  });

  it('use weight 300 only from 20 px up (VL-3 AC2)', () => {
    for (const t of fresh().sources.type.filter((e) => e.kind === 'scale' && e.weight === 300)) expect(t.size).toBeGreaterThanOrEqual(20);
  });

  it('keep heights as sizes and give the pointer targets of VL-4 AC3', () => {
    const sizes = Object.fromEntries(fresh().sources.size.map((s) => [s.name, s.px]));
    expect(sizes).toMatchObject({ 'top-bar-height': 44, 'control-height': 36, 'target-min': 24, 'target-min-narrow': 44, 'focus-ring-width': 2, 'focus-ring-offset': 2 });
  });

  it('derive the pairs of the Bootstrap map', () => {
    const { sources } = fresh();
    const derived = bootstrapTokenPairs(sources);
    expect(derived.find((p) => p.id === 'bs-body')).toMatchObject({ fg: 'ink-strong', bg: 'surface-canvas', role: 'text' });
    expect(allPairs(sources).length).toBe(sources.pairs.length + derived.length);
    expect(colorMap(sources).get('primary').light).toBe('#00aeef');
  });
});

describe('validateSources', () => {
  it('reports a duplicate token name across files', () => {
    expect(problemsFor((s) => s.space.push({ name: 'radius-md', px: 3, usage: 'clash' }))).toContainEqual(expect.stringMatching(/"radius-md" is already used in tokens\/space\.json/));
  });

  it('reports a duplicate colour name', () => {
    expect(problemsFor((s) => s.color.push({ ...s.color[0] }))).toContainEqual(expect.stringMatching(/"surface-canvas" is already used/));
  });

  it('reports a duplicate type scale entry', () => {
    expect(problemsFor((s) => s.type.push({ ...s.type.find((t) => t.kind === 'scale') }))).toContain('tokens/type.json: duplicate type scale name');
  });

  it('reports a missing required colour (removing a token is breaking)', () => {
    const problems = problemsFor((s) => { s.color = s.color.filter((c) => c.name !== 'focus-ring'); });
    expect(problems).toContain('tokens/color.json: required token "focus-ring" is missing');
  });

  it.each([
    ['a colour that is not hex or rgba', (s) => { s.color[0].light = 'red'; }],
    ['a 3-digit hex colour', (s) => { s.color[0].dark = '#fff'; }],
    ['a colour with an unknown property', (s) => { s.color[0].extra = 1; }],
    ['a forced colour outside the system colour list', (s) => { s.color[0].forced = 'Teal'; }],
    ['a rule injection in a shadow', (s) => { s.shadow[0].light = '0 0 1px red; } body { display:none'; }],
    ['a rule injection in a font family', (s) => { s.type[0].value = 'serif; } * { color: red'; }],
    ['a motion value that is not a duration or easing', (s) => { s.motion[0].value = 'url(x)'; }],
    ['a name with upper case', (s) => { s.space[0].name = 'Space-1'; }],
    ['a pair with an unknown role', (s) => { s.pairs[0].role = 'decor'; }],
    ['a pair with a threshold of its own', (s) => { s.pairs[0].threshold = 1; }],
    ['a type weight outside the allowed set', (s) => { s.type[2].weight = 100; }],
    ['an empty list', (s) => { s.radius = []; }],
  ])('rejects %s', (_label, mutate) => {
    expect(problemsFor(mutate).length).toBeGreaterThan(0);
  });

  it('reports a missing source', () => {
    const model = fresh();
    delete model.sources.size;
    expect(validateSources(model)).toContain('tokens/size.json: missing');
  });

  it('reports a pair id used twice', () => {
    expect(problemsFor((s) => s.pairs.push({ ...s.pairs[0] }))).toContainEqual(expect.stringMatching(/duplicate pair id/));
  });

  it('reports Bootstrap mapping errors', () => {
    expect(problemsFor((s) => s.bootstrap.entries.push({ bs: '--bs-new', token: 'nope' }))).toContainEqual(expect.stringMatching(/unknown token "nope"/));
    expect(problemsFor((s) => s.bootstrap.entries.push({ bs: '--bs-new-rgb', token: 'space-1', channels: true }))).toContainEqual(expect.stringMatching(/which is not a colour/));
    expect(problemsFor((s) => s.bootstrap.entries.push({ bs: '--bs-new-rgb', token: 'surface-glass', channels: true }))).toContainEqual(expect.stringMatching(/translucent colour/));
    expect(problemsFor((s) => s.bootstrap.entries.push({ ...s.bootstrap.entries[0] }))).toContain('bootstrap-map.json: a --bs-* variable is mapped twice');
    expect(problemsFor((s) => s.bootstrap.pairs.push({ id: 'x', fg: '--bs-body-color', bg: '--bs-nowhere', role: 'text' }))).toContainEqual(expect.stringMatching(/--bs-nowhere, which is not mapped to a colour token/));
    expect(problemsFor((s) => s.bootstrap.pairs.push({ id: 'x', fg: '--bs-font-sans-serif', bg: '--bs-body-bg', role: 'text' }))).toContainEqual(expect.stringMatching(/not mapped to a colour token/));
  });
});

describe('loadSources', () => {
  it('lists every source file', () => {
    expect(Object.keys(TOKEN_FILES)).toEqual(['color', 'type', 'space', 'radius', 'shadow', 'motion', 'size', 'pairs', 'bootstrap']);
  });

  it('fails with the file name when a source is missing or not JSON', () => {
    const dir = mkdtempSync(join(tmpdir(), 'design-'));
    cpSync(designDir, dir, { recursive: true });
    writeFileSync(join(dir, 'tokens/space.json'), '{ not json');
    expect(() => loadSources(dir)).toThrow(/cannot read tokens\/space\.json/);
    expect(() => loadSources(join(dir, 'missing'))).toThrow(/cannot read tokens\.schema\.json/);
  });
});
