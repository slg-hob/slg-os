// Every --bs-* variable that platform code uses must be mapped to a --slg-* token (PRD-0005 VL-5 AC2),
// otherwise a theme switch would leave it on Bootstrap's own colours.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const SOURCE_FILE = /\.(css|scss|ts|tsx|mjs|js|html)$/;
const TEST_FILE = /\.test\.(ts|tsx|mjs|js)$/;

/** All source files below dir (tests excluded), as { path, text }. A missing directory yields no files. */
export function readSources(dir) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return [];
  }
  return entries.flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'generated' || name === 'node_modules' ? [] : readSources(path);
    return SOURCE_FILE.test(name) && !TEST_FILE.test(name) ? [{ path, text: readFileSync(path, 'utf8') }] : [];
  });
}

/**
 * @param {{path: string, text: string}[]} files
 * @param {Set<string>} mapped names of the mapped --bs-* variables
 * @returns {string[]} problems, one per file and variable
 */
export function findUnmappedBootstrapVariables(files, mapped) {
  const problems = [];
  for (const { path, text } of files) {
    for (const name of new Set(text.match(/--bs-[a-z0-9-]+/g) ?? [])) {
      if (!mapped.has(name)) problems.push(`${path}: ${name} is used but not mapped to a --slg-* token in design/bootstrap-map.json`);
    }
  }
  return problems;
}
