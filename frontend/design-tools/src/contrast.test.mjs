import { readFileSync } from 'node:fs';
import { composite, contrastRatio, evaluatePairs, formatFailures, luminance, MODES, parseColor, ROLE_THRESHOLDS, valueIn } from './contrast.mjs';
import { allPairs, colorMap, loadSources } from './tokens.mjs';

const designDir = new URL('../../../design', import.meta.url).pathname;
const { sources } = loadSources(designDir);
const colors = colorMap(sources);

const pair = (overrides) => ({ id: 'p', fg: 'ink-strong', bg: 'surface-raised', role: 'text', ...overrides });
const ratioOf = (results, id, mode, backdrop = null) => results.find((r) => r.id === id && r.mode === mode && r.backdrop === backdrop)?.ratio;

describe('parseColor', () => {
  it.each([
    ['#000000', { r: 0, g: 0, b: 0, a: 1 }],
    ['#FFFFFF', { r: 255, g: 255, b: 255, a: 1 }],
    ['#00aeef', { r: 0, g: 174, b: 239, a: 1 }],
    ['rgb(1, 2, 3)', { r: 1, g: 2, b: 3, a: 1 }],
    ['rgba(255, 255, 255, 0.78)', { r: 255, g: 255, b: 255, a: 0.78 }],
    ['rgba(0,0,0,.4)', { r: 0, g: 0, b: 0, a: 0.4 }],
  ])('parses %s', (text, expected) => {
    expect(parseColor(text)).toEqual(expected);
  });

  it.each(['red', '#fff', '#12345', 'rgba(1,2)', 'rgba(256, 0, 0, 1)', 'rgba(0, 0, 0, 2)', '', 'var(--x)'])('rejects %j', (text) => {
    expect(() => parseColor(text)).toThrow(/colour|not a colour/);
  });

  it('rejects a value that is not a string', () => {
    expect(() => parseColor(undefined)).toThrow(/not a colour/);
  });
});

describe('contrast maths', () => {
  it('gives 21:1 for black on white and 1:1 for equal colours', () => {
    expect(contrastRatio(parseColor('#000000'), parseColor('#ffffff'))).toBeCloseTo(21, 5);
    expect(contrastRatio(parseColor('#777777'), parseColor('#777777'))).toBe(1);
  });

  it('is symmetric', () => {
    const a = parseColor('#00729c');
    const b = parseColor('#f2f2f2');
    expect(contrastRatio(a, b)).toBe(contrastRatio(b, a));
  });

  it('computes the luminance of the extremes', () => {
    expect(luminance(parseColor('#000000'))).toBe(0);
    expect(luminance(parseColor('#ffffff'))).toBeCloseTo(1, 10);
  });

  it('composites with the sRGB alpha formula and refuses a translucent background', () => {
    expect(composite(parseColor('rgba(255, 255, 255, 0.5)'), parseColor('#000000'))).toEqual({ r: 128, g: 128, b: 128, a: 1 });
    expect(composite(parseColor('#336699'), parseColor('#000000'))).toEqual({ r: 51, g: 102, b: 153, a: 1 });
    expect(() => composite(parseColor('#fff'.padEnd(7, 'f')), parseColor('rgba(0,0,0,0.5)'))).toThrow(/opaque/);
  });
});

describe('the normative values of PRD-0005 VL-2 AC2', () => {
  const results = evaluatePairs(colors, allPairs(sources));

  it.each([
    ['ink-strong-on-raised', 'light', 15.13],
    ['ink-strong-on-raised', 'dark', 11.29],
    ['ink-muted-on-raised', 'light', 7.0],
    ['ink-muted-on-raised', 'dark', 6.87],
    ['ink-muted-on-raised-high', 'dark', 5.64],
    ['link-on-raised', 'light', 5.4],
    ['link-on-canvas', 'light', 4.83],
    ['link-on-raised', 'dark', 7.05],
    ['control-border-on-raised', 'light', 4.74],
    ['control-border-on-canvas', 'light', 4.24],
    ['control-border-on-raised', 'dark', 4.49],
    ['control-border-on-raised-high', 'dark', 3.68],
    ['on-primary-on-primary', 'light', 5.98],
  ])('%s in %s is %s:1', (id, mode, expected) => {
    expect(ratioOf(results, id, mode)).toBeCloseTo(expected, 2);
  });

  it('keeps ink-strong readable on glass over black, white and the wallpaper bounds in both themes', () => {
    for (const mode of ['light', 'dark']) {
      for (const backdrop of ['black', 'white', 'wallpaper-darkest', 'wallpaper-lightest']) {
        expect(ratioOf(results, 'ink-strong-on-glass', mode, backdrop)).toBeGreaterThanOrEqual(4.5);
      }
    }
    expect(ratioOf(results, 'ink-strong-on-glass', 'dark', 'white')).toBeCloseTo(5.79, 2);
  });

  it('shows why only ink-strong may sit on glass: muted ink fails over some backdrop', () => {
    const muted = evaluatePairs(colors, [pair({ id: 'muted-on-glass', fg: 'ink-muted', bg: 'surface-glass', over: ['black', 'white'] })]);
    expect(muted.some((r) => !r.pass)).toBe(true);
  });

  it('passes every declared pair in all four modes', () => {
    expect(formatFailures(results)).toEqual([]);
    expect(new Set(results.map((r) => r.mode))).toEqual(new Set(MODES));
  });

  it('keeps every status pair above 7.7:1 as the PRD states', () => {
    const status = results.filter((r) => r.id.startsWith('status-'));
    expect(status.length).toBeGreaterThan(0);
    for (const r of status) expect(r.ratio).toBeGreaterThanOrEqual(7.7);
  });
});

describe('evaluatePairs', () => {
  it('detects a failing pair and names it in the failure text', () => {
    const weak = new Map(colors);
    weak.set('ink-muted', { ...colors.get('ink-muted'), light: '#999999' });
    const results = evaluatePairs(weak, [pair({ id: 'weak', fg: 'ink-muted' })]);
    const failures = formatFailures(results);
    expect(failures.length).toBeGreaterThan(0);
    expect(failures[0]).toMatch(/^weak \[light\]: \d\.\d\d:1, needs 4\.5:1 \(text\)/);
  });

  it('uses the role threshold, and a role with a lower threshold passes a weaker pair', () => {
    const grey = new Map([['a', { light: '#767676', dark: '#767676' }], ['b', { light: '#ffffff', dark: '#ffffff' }]]);
    const asText = evaluatePairs(grey, [{ id: 'x', fg: 'a', bg: 'b', role: 'text', modes: ['light'] }]);
    const asUi = evaluatePairs(grey, [{ id: 'x', fg: 'a', bg: 'b', role: 'ui', modes: ['light'] }]);
    expect(asText[0].pass).toBe(true); // 4.54:1
    const lighter = new Map([['a', { light: '#949494', dark: '#949494' }], ['b', { light: '#ffffff', dark: '#ffffff' }]]);
    expect(evaluatePairs(lighter, [{ id: 'x', fg: 'a', bg: 'b', role: 'text', modes: ['light'] }])[0].pass).toBe(false);
    expect(evaluatePairs(lighter, [{ id: 'x', fg: 'a', bg: 'b', role: 'ui', modes: ['light'] }])[0].pass).toBe(true);
    expect(asUi[0].threshold).toBe(ROLE_THRESHOLDS.ui);
  });

  it('falls back to the base value in high-contrast modes without an override', () => {
    expect(valueIn({ light: '#111111', dark: '#eeeeee' }, 'light-hc')).toBe('#111111');
    expect(valueIn({ light: '#111111', dark: '#eeeeee', darkHc: '#ffffff' }, 'dark-hc')).toBe('#ffffff');
    expect(() => valueIn({ light: '#111111', dark: '#eeeeee' }, 'sepia')).toThrow(/unknown mode/);
  });

  it('applies the high-contrast override in the high-contrast modes', () => {
    const results = evaluatePairs(colors, [pair({ id: 'muted', fg: 'ink-muted' })]);
    expect(ratioOf(results, 'muted', 'light-hc')).toBeGreaterThan(ratioOf(results, 'muted', 'light'));
  });

  it.each([
    ['an unknown foreground token', pair({ fg: 'nope' }), /unknown colour token "nope"/],
    ['an unknown background token', pair({ bg: 'nope' }), /unknown colour token "nope"/],
    ['an unknown role', pair({ role: 'decor' }), /unknown role "decor"/],
    ['a translucent background without backdrops', pair({ bg: 'surface-glass' }), /needs "over"/],
    ['backdrops on an opaque background', pair({ over: ['black'] }), /background is opaque/],
    ['an unknown backdrop', pair({ bg: 'surface-glass', over: ['teal'] }), /unknown backdrop "teal"/],
  ])('throws for %s', (_label, bad, message) => {
    expect(() => evaluatePairs(colors, [bad])).toThrow(message);
  });

  it('composites a translucent foreground over the resulting surface', () => {
    const set = new Map([
      ['edge', { light: 'rgba(0, 0, 0, 0.5)', dark: 'rgba(0, 0, 0, 0.5)' }],
      ['paper', { light: '#ffffff', dark: '#ffffff' }],
    ]);
    const [result] = evaluatePairs(set, [{ id: 'edge-on-paper', fg: 'edge', bg: 'paper', role: 'ui', modes: ['light'] }]);
    expect(result.ratio).toBeCloseTo(contrastRatio(parseColor('#808080'), parseColor('#ffffff')), 5);
  });

  it('keeps the source file of the pairs free of explicit thresholds so a pair cannot lower its own', () => {
    const raw = JSON.parse(readFileSync(`${designDir}/pairs.json`, 'utf8'));
    for (const p of raw) expect(p).not.toHaveProperty('threshold');
  });
});
