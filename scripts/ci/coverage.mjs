#!/usr/bin/env node
// Coverage tooling (PRD-0001 NFR-2, PRD-0003 SK-2 AC2):
//   coverage.mjs report <out.json>            build a report from JaCoCo XML and Vitest json-summary files
//   coverage.mjs merge <out> <a> <b> ...      merge several reports into one
//   coverage.mjs compare <current> <baseline> fail when a module or package dropped by more than 2 points
//   coverage.mjs critical                     fail when the gateway/session/security package trees are below 90 % line coverage
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const MAX_DROP = 2;
export const CRITICAL_MINIMUM = 90;
export const CRITICAL_SEGMENTS = ['gateway', 'session', 'security'];

const percentage = (covered, missed) => (covered + missed === 0 ? 100 : Math.round((covered / (covered + missed)) * 10000) / 100);

/** Parses a JaCoCo XML report into line counters for the bundle and for each package. */
export function parseJacoco(xml) {
  const packages = {};
  const packageRe = /<package name="([^"]+)">([\s\S]*?)<\/package>/g;
  let lastEnd = 0;
  for (const match of xml.matchAll(packageRe)) {
    const counters = [...match[2].matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/g)];
    const last = counters.at(-1);
    packages[match[1].replaceAll('/', '.')] = last ? { missed: Number(last[1]), covered: Number(last[2]) } : { missed: 0, covered: 0 };
    lastEnd = match.index + match[0].length;
  }
  const tail = [...xml.slice(lastEnd).matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/g)].at(-1);
  const bundle = tail ? { missed: Number(tail[1]), covered: Number(tail[2]) } : { missed: 0, covered: 0 };
  return { bundle, packages };
}

/** Parses a Vitest coverage-summary.json (json-summary reporter) into line counters. */
export function parseVitest(json) {
  const lines = json?.total?.lines;
  if (!lines) throw new Error('coverage summary has no total.lines');
  return { covered: lines.covered, missed: lines.total - lines.covered };
}

/** Builds the comparable report: { modules: { name: pct }, packages: { "module:package": pct } }. */
export function buildReport({ jacoco = {}, vitest = {} }) {
  const modules = {};
  const packages = {};
  for (const [name, xml] of Object.entries(jacoco)) {
    const parsed = parseJacoco(xml);
    modules[name] = percentage(parsed.bundle.covered, parsed.bundle.missed);
    for (const [pkg, counter] of Object.entries(parsed.packages)) packages[`${name}:${pkg}`] = percentage(counter.covered, counter.missed);
  }
  for (const [name, json] of Object.entries(vitest)) {
    const counter = parseVitest(json);
    modules[name] = percentage(counter.covered, counter.missed);
  }
  return { modules, packages };
}

/** Merges several reports; later reports win for equal keys. */
export function merge(...reports) {
  return { modules: Object.assign({}, ...reports.map((r) => r.modules)), packages: Object.assign({}, ...reports.map((r) => r.packages)) };
}

/** Compares a report with the baseline. Drops above MAX_DROP and vanished modules fail; vanished packages warn. */
export function compare(current, baseline, maxDrop = MAX_DROP) {
  const failures = [];
  const warnings = [];
  for (const kind of ['modules', 'packages']) {
    for (const [name, before] of Object.entries(baseline?.[kind] ?? {})) {
      const after = current?.[kind]?.[name];
      if (after === undefined) {
        (kind === 'modules' ? failures : warnings).push(`${kind.slice(0, -1)} "${name}" is missing from the current report`);
      } else if (before - after > maxDrop) {
        failures.push(`${kind.slice(0, -1)} "${name}" dropped from ${before} % to ${after} % (limit ${maxDrop} points)`);
      }
    }
  }
  return { failures, warnings };
}

/** Checks the line coverage of the critical package trees across all modules (90 %). Empty trees pass. */
export function criticalViolations(jacoco, minimum = CRITICAL_MINIMUM) {
  const trees = Object.fromEntries(CRITICAL_SEGMENTS.map((segment) => [segment, { covered: 0, missed: 0 }]));
  for (const [name, xml] of Object.entries(jacoco)) {
    for (const [pkg, counter] of Object.entries(parseJacoco(xml).packages)) {
      for (const segment of CRITICAL_SEGMENTS) {
        if (pkg.split('.').includes(segment)) {
          trees[segment].covered += counter.covered;
          trees[segment].missed += counter.missed;
        }
      }
    }
    void name;
  }
  return Object.entries(trees)
    .filter(([, c]) => c.covered + c.missed > 0 && percentage(c.covered, c.missed) < minimum)
    .map(([segment, c]) => `package tree "${segment}" has ${percentage(c.covered, c.missed)} % line coverage (minimum ${minimum} %)`);
}

function collect(root) {
  const jacoco = {};
  const vitest = {};
  const backend = join(root, 'backend');
  if (existsSync(backend)) {
    for (const module of readdirSync(backend)) {
      const file = join(backend, module, 'target', 'site', 'jacoco', 'jacoco.xml');
      if (existsSync(file)) jacoco[`backend/${module}`] = readFileSync(file, 'utf8');
    }
  }
  const frontend = join(root, 'frontend');
  if (existsSync(frontend)) {
    for (const pkg of readdirSync(frontend)) {
      const file = join(frontend, pkg, 'coverage', 'coverage-summary.json');
      if (existsSync(file)) vitest[`frontend/${pkg}`] = JSON.parse(readFileSync(file, 'utf8'));
    }
  }
  return { jacoco, vitest };
}

function main(args) {
  const [command, a, b] = args;
  const root = resolve('.');
  if (command === 'report') {
    const report = buildReport(collect(root));
    if (Object.keys(report.modules).length === 0) throw new Error('no coverage data found; run the tests with coverage first');
    writeFileSync(a, `${JSON.stringify(report, null, 2)}\n`);
    console.log(`coverage report written to ${a} (${Object.keys(report.modules).length} modules)`);
  } else if (command === 'merge') {
    const reports = args.slice(2).map((file) => JSON.parse(readFileSync(file, 'utf8')));
    writeFileSync(a, `${JSON.stringify(merge(...reports), null, 2)}\n`);
    console.log(`merged ${reports.length} reports into ${a}`);
  } else if (command === 'compare') {
    const { failures, warnings } = compare(JSON.parse(readFileSync(a, 'utf8')), JSON.parse(readFileSync(b, 'utf8')));
    warnings.forEach((w) => console.warn(`warning: ${w}`));
    if (failures.length > 0) {
      console.error(failures.map((f) => `- ${f}`).join('\n'));
      process.exit(1);
    }
    console.log('coverage did not regress');
  } else if (command === 'critical') {
    const violations = criticalViolations(collect(root).jacoco);
    if (violations.length > 0) {
      console.error(violations.map((v) => `- ${v}`).join('\n'));
      process.exit(1);
    }
    console.log('critical package trees meet the minimum (empty trees pass; the rule is present)');
  } else {
    console.error('usage: coverage.mjs report <out> | merge <out> <in...> | compare <current> <baseline> | critical');
    process.exit(2);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
