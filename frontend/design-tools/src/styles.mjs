// Builds the design-system stylesheet: tokens, Bootstrap compiled from SCSS with the generated maps, Bootstrap runtime mapping.
// Output: dist/design-system.css (PRD-0005 VL-5 AC2). No network access; Bootstrap comes from node_modules (pinned by the lock file).
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { gzipSync } from 'node:zlib';
import * as sass from 'sass';

/** Gzip size budget of the stylesheet in kilobytes (PRD-0005 NFR-V3). */
export const CSS_GZIP_BUDGET_KB = 60;

/** Deprecations of Bootstrap 5.3 sources under current Dart Sass. They come from Bootstrap, not from our files. */
const SILENCED = ['import', 'global-builtin', 'color-functions', 'if-function'];

/** Size in bytes of the content compressed with gzip level 9. */
export const gzipSize = (css) => gzipSync(Buffer.from(css), { level: 9 }).length;

/** Returns a problem string when the stylesheet exceeds the budget, otherwise undefined. */
export function checkBudget(css, budgetKb = CSS_GZIP_BUDGET_KB) {
  const bytes = gzipSize(css);
  return bytes > budgetKb * 1024 ? `stylesheet is ${(bytes / 1024).toFixed(1)} KB gzip, budget is ${budgetKb} KB` : undefined;
}

/**
 * Static check of the built CSS (PRD-0005 VL-13 AC4): no data: URLs, no network URLs, no @import of a URL.
 * @returns {string[]} problems
 */
export function findForbiddenUrls(css) {
  const problems = [];
  if (/url\(\s*["']?\s*data:/i.test(css)) problems.push('the stylesheet contains a data: URL');
  if (/url\(\s*["']?\s*(?:https?:)?\/\//i.test(css)) problems.push('the stylesheet contains a network URL');
  if (/@import\s+(?:url\()?\s*["']?\s*(?:https?:)?\/\//i.test(css)) problems.push('the stylesheet imports a network URL');
  return problems;
}

/**
 * Compiles the stylesheet of the design-system package and writes dist/design-system.css.
 * @param {string} packageDir directory of the design-system package
 * @returns {{css: string, file: string, gzipBytes: number}}
 */
export function buildStyles(packageDir) {
  const nodeModules = dirname(dirname(createRequire(import.meta.url).resolve('bootstrap/package.json')));
  const compiled = sass.compile(join(packageDir, 'src/styles/index.scss'), {
    loadPaths: [nodeModules],
    style: 'compressed',
    quietDeps: true,
    silenceDeprecations: SILENCED,
  });
  const read = (name) => readFileSync(join(packageDir, 'src/generated', name), 'utf8');
  // Order matters: tokens, then Bootstrap (which declares --bs-*), then the mapping that overrides them from the tokens.
  const css = [read('tokens.css'), compiled.css, read('bootstrap-map.css')].join('\n');
  const file = join(packageDir, 'dist/design-system.css');
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, css);
  return { css, file, gzipBytes: gzipSize(css) };
}
