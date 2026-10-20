// Loading and validating the token sources in design/tokens (PRD-0005 VL-2, VL-4, VL-5).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { validate } from './schema.mjs';
import { parseColor } from './contrast.mjs';

/** Source files and the schema definition that validates each. The key is the name used in the loaded model. */
export const TOKEN_FILES = Object.freeze({
  color: { file: 'tokens/color.json', def: 'colorList' },
  type: { file: 'tokens/type.json', def: 'typeList' },
  space: { file: 'tokens/space.json', def: 'pixelsList' },
  radius: { file: 'tokens/radius.json', def: 'pixelsList' },
  shadow: { file: 'tokens/shadow.json', def: 'shadowList' },
  motion: { file: 'tokens/motion.json', def: 'motionList' },
  size: { file: 'tokens/size.json', def: 'pixelsList' },
  pairs: { file: 'pairs.json', def: 'pairs' },
  bootstrap: { file: 'bootstrap-map.json', def: 'bootstrapMap' },
});

/** Colour tokens that must exist (VL-2 AC1). Removing or renaming one is a breaking change (VL-5 AC4). */
export const REQUIRED_COLORS = Object.freeze([
  'surface-canvas', 'surface-raised', 'surface-raised-high', 'surface-glass', 'surface-glass-solid', 'surface-scrim',
  'edge-glass', 'divider', 'control-border', 'ink-strong', 'ink-muted', 'link', 'focus-ring', 'primary', 'on-primary',
  'primary-hover', 'primary-pressed', 'primary-disabled', 'on-primary-disabled',
  ...['default', 'success', 'warning', 'danger', 'neutral'].flatMap((tone) => [`status-${tone}-text`, `status-${tone}-bg`]),
  ...['danger', 'success', 'warning'].flatMap((tone) => [`solid-${tone}-fill`, `solid-${tone}-text`]),
  'wallpaper-darkest', 'wallpaper-lightest',
]);

/** Reads the schema and all sources below `designDir` (the directory "design"). Throws on unreadable or non-JSON files. */
export function loadSources(designDir) {
  const read = (file) => {
    try {
      return JSON.parse(readFileSync(join(designDir, file), 'utf8'));
    } catch (error) {
      throw new Error(`cannot read ${file}: ${error.message}`, { cause: error });
    }
  };
  const schema = read('tokens.schema.json');
  const sources = {};
  for (const [key, { file }] of Object.entries(TOKEN_FILES)) sources[key] = read(file);
  return { schema, sources };
}

const isOpaque = (value) => parseColor(value).a === 1;

/**
 * Validates all sources: schema, unique names, required colours, references between files.
 * @returns {string[]} problems; empty when the sources are valid
 */
export function validateSources({ schema, sources }) {
  const problems = [];
  for (const [key, { file, def }] of Object.entries(TOKEN_FILES)) {
    if (sources[key] === undefined) {
      problems.push(`${file}: missing`);
      continue;
    }
    problems.push(...validate(schema, { $ref: `#/$defs/${def}` }, sources[key], file));
  }
  if (problems.length > 0) return problems; // semantic checks assume the shape

  const names = new Map();
  for (const key of ['color', 'type', 'space', 'radius', 'shadow', 'motion', 'size']) {
    for (const entry of sources[key]) {
      if (key === 'type' && entry.kind === 'scale') continue; // scale entries live in the --slg-type-* namespace
      const where = `${TOKEN_FILES[key].file}`;
      if (names.has(entry.name)) problems.push(`${where}: token name "${entry.name}" is already used in ${names.get(entry.name)}`);
      else names.set(entry.name, where);
    }
  }
  const scaleNames = sources.type.filter((e) => e.kind === 'scale').map((e) => e.name);
  if (new Set(scaleNames).size !== scaleNames.length) problems.push('tokens/type.json: duplicate type scale name');

  const colors = new Set(sources.color.map((c) => c.name));
  for (const required of REQUIRED_COLORS) {
    if (!colors.has(required)) problems.push(`tokens/color.json: required token "${required}" is missing`);
  }
  for (const c of sources.color) {
    for (const field of ['light', 'dark', 'lightHc', 'darkHc']) {
      if (c[field] !== undefined && /rgba?\(/.test(c[field])) {
        try {
          parseColor(c[field]);
        } catch (error) {
          problems.push(`tokens/color.json: ${c.name}.${field}: ${error.message}`);
        }
      }
    }
  }

  const ids = new Set();
  for (const pair of sources.pairs) {
    if (ids.has(pair.id)) problems.push(`pairs.json: duplicate pair id "${pair.id}"`);
    ids.add(pair.id);
  }
  const everyToken = new Set([...names.keys()]);
  for (const entry of sources.bootstrap.entries) {
    if (!everyToken.has(entry.token)) problems.push(`bootstrap-map.json: ${entry.bs} refers to the unknown token "${entry.token}"`);
    if (entry.channels) {
      const color = sources.color.find((c) => c.name === entry.token);
      if (!color) problems.push(`bootstrap-map.json: ${entry.bs} wants channels of "${entry.token}", which is not a colour`);
      else if (![color.light, color.dark, color.lightHc, color.darkHc].filter(Boolean).every(isOpaque)) {
        problems.push(`bootstrap-map.json: ${entry.bs} wants channels of the translucent colour "${entry.token}"`);
      }
    }
  }
  const bsNames = sources.bootstrap.entries.map((e) => e.bs);
  if (new Set(bsNames).size !== bsNames.length) problems.push('bootstrap-map.json: a --bs-* variable is mapped twice');
  const colorNames = new Set(sources.color.map((c) => c.name));
  const mapped = new Set(sources.bootstrap.entries.filter((e) => !e.channels && colorNames.has(e.token)).map((e) => e.bs));
  for (const pair of sources.bootstrap.pairs) {
    for (const side of [pair.fg, pair.bg]) {
      if (!mapped.has(side)) problems.push(`bootstrap-map.json: pair "${pair.id}" uses ${side}, which is not mapped to a colour token`);
    }
  }
  return problems;
}

/** Colour tokens by name, as the contrast evaluation needs them. */
export const colorMap = (sources) => new Map(sources.color.map((c) => [c.name, c]));

/**
 * Pairs of the Bootstrap map expressed as token pairs: a pair of --bs-* variables becomes the pair of the tokens they map to.
 * The id is prefixed so a failure names the Bootstrap variable pair.
 */
export function bootstrapTokenPairs(sources) {
  const tokenOf = new Map(sources.bootstrap.entries.filter((e) => !e.channels).map((e) => [e.bs, e.token]));
  return sources.bootstrap.pairs.map((pair) => ({ id: pair.id, fg: tokenOf.get(pair.fg), bg: tokenOf.get(pair.bg), role: pair.role,
    ...(pair.over ? { over: pair.over } : {}), ...(pair.modes ? { modes: pair.modes } : {}) }));
}

/** All pairs to evaluate: the declared list plus the pairs derived from the Bootstrap mapping. */
export const allPairs = (sources) => [...sources.pairs, ...bootstrapTokenPairs(sources)];
