// Contrast evaluation on tokens, not on rendering (PRD-0005 VL-2 AC5). WCAG 2.2 relative luminance and the sRGB alpha formula.
// Blur is ignored on purpose: it only narrows the backdrop extremes that are checked.

/** Minimum contrast ratio per pair role. Roles are fixed here so a pair list cannot lower its own threshold. */
export const ROLE_THRESHOLDS = Object.freeze({
  text: 4.5,
  'text-enhanced': 7.7,
  'large-text': 3,
  ui: 3,
  hairline: 1.5,
  disabled: 1.5,
});

/** Modes in which every pair is evaluated. The "-hc" modes apply under prefers-contrast: more. */
export const MODES = Object.freeze(['light', 'dark', 'light-hc', 'dark-hc']);

const HEX = /^#([0-9a-f]{6})$/i;
const RGBA = /^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*(\d(?:\.\d+)?|\.\d+)\s*)?\)$/i;

/**
 * Parses #rrggbb, rgb() or rgba() into { r, g, b, a } (channels 0..255, alpha 0..1).
 * @param {string} value
 */
export function parseColor(value) {
  if (typeof value !== 'string') throw new Error(`not a colour: ${String(value)}`);
  const hex = HEX.exec(value);
  if (hex) {
    const n = Number.parseInt(hex[1], 16);
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 };
  }
  const rgba = RGBA.exec(value);
  if (rgba) {
    const [r, g, b] = [rgba[1], rgba[2], rgba[3]].map(Number);
    const a = rgba[4] === undefined ? 1 : Number(rgba[4]);
    if (r > 255 || g > 255 || b > 255 || a > 1) throw new Error(`colour channel out of range: ${value}`);
    return { r, g, b, a };
  }
  throw new Error(`not a colour: ${value}`);
}

/** Composites a (possibly translucent) foreground over an opaque background with the sRGB alpha formula. */
export function composite(fg, bg) {
  if (bg.a !== 1) throw new Error('the background of a composite must be opaque');
  const mix = (f, b) => Math.round(f * fg.a + b * (1 - fg.a));
  return { r: mix(fg.r, bg.r), g: mix(fg.g, bg.g), b: mix(fg.b, bg.b), a: 1 };
}

const channel = (c) => {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};

/** WCAG relative luminance of an opaque colour. */
export function luminance({ r, g, b }) {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** WCAG contrast ratio of two opaque colours (1..21). */
export function contrastRatio(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Value of a colour token in a mode. The high-contrast modes fall back to the base theme when the token has no override.
 * @param {{light: string, dark: string, lightHc?: string, darkHc?: string}} token
 * @param {string} mode
 */
export function valueIn(token, mode) {
  switch (mode) {
    case 'light': return token.light;
    case 'dark': return token.dark;
    case 'light-hc': return token.lightHc ?? token.light;
    case 'dark-hc': return token.darkHc ?? token.dark;
    default: throw new Error(`unknown mode ${mode}`);
  }
}

const BACKDROP_KEYWORDS = { black: '#000000', white: '#ffffff' };

/**
 * Evaluates every declared pair in every mode and returns one result per (pair, mode, backdrop).
 * A pair whose background is translucent must declare the backdrops it is composited over ("over");
 * a translucent foreground is composited over the resulting background.
 * @param {Map<string, object>} colors colour tokens by name
 * @param {Array<{id: string, fg: string, bg: string, role: string, over?: string[], modes?: string[]}>} pairs
 */
export function evaluatePairs(colors, pairs) {
  const results = [];
  const token = (name, pairId) => {
    const t = colors.get(name);
    if (!t) throw new Error(`pair "${pairId}" refers to the unknown colour token "${name}"`);
    return t;
  };
  for (const pair of pairs) {
    const threshold = ROLE_THRESHOLDS[pair.role];
    if (threshold === undefined) throw new Error(`pair "${pair.id}" has the unknown role "${pair.role}"`);
    const fgToken = token(pair.fg, pair.id);
    const bgToken = token(pair.bg, pair.id);
    for (const mode of pair.modes ?? MODES) {
      const bgColor = parseColor(valueIn(bgToken, mode));
      const fgColor = parseColor(valueIn(fgToken, mode));
      if (bgColor.a === 1 && pair.over) throw new Error(`pair "${pair.id}" declares backdrops but its background is opaque`);
      if (bgColor.a < 1 && !pair.over) throw new Error(`pair "${pair.id}" has a translucent background and needs "over"`);
      const backdrops = pair.over ?? [null];
      for (const backdrop of backdrops) {
        let surface = bgColor;
        if (backdrop !== null) {
          const raw = BACKDROP_KEYWORDS[backdrop] ?? (colors.has(backdrop) ? valueIn(colors.get(backdrop), mode) : undefined);
          if (raw === undefined) throw new Error(`pair "${pair.id}" refers to the unknown backdrop "${backdrop}"`);
          surface = composite(bgColor, parseColor(raw));
        }
        const ratio = contrastRatio(fgColor.a < 1 ? composite(fgColor, surface) : fgColor, surface);
        results.push({ id: pair.id, mode, backdrop, role: pair.role, threshold, ratio, pass: ratio >= threshold });
      }
    }
  }
  return results;
}

/** Formats the failing results for the console. */
export function formatFailures(results) {
  return results
    .filter((r) => !r.pass)
    .map((r) => `${r.id} [${r.mode}${r.backdrop ? ` over ${r.backdrop}` : ''}]: ${r.ratio.toFixed(2)}:1, needs ${r.threshold}:1 (${r.role})`);
}
