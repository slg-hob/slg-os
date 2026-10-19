import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildReport, compare, merge, criticalViolations, parseJacoco, parseVitest } from '../coverage.mjs';

const pkg = (name, missed, covered) =>
  `<package name="${name}"><class name="x"><counter type="LINE" missed="1" covered="1"/></class><counter type="INSTRUCTION" missed="9" covered="9"/><counter type="LINE" missed="${missed}" covered="${covered}"/></package>`;
const xml = (packages, bundleMissed, bundleCovered) =>
  `<report name="m">${packages}<counter type="INSTRUCTION" missed="0" covered="5"/><counter type="LINE" missed="${bundleMissed}" covered="${bundleCovered}"/></report>`;

test('parseJacoco reads package and bundle line counters', () => {
  const parsed = parseJacoco(xml(pkg('a/b/gateway', 1, 9) + pkg('a/b/core', 0, 4), 1, 13));
  assert.deepEqual(parsed.bundle, { missed: 1, covered: 13 });
  assert.deepEqual(parsed.packages['a.b.gateway'], { missed: 1, covered: 9 });
  assert.deepEqual(parsed.packages['a.b.core'], { missed: 0, covered: 4 });
});

test('parseJacoco tolerates a report without packages or counters', () => {
  assert.deepEqual(parseJacoco('<report name="x"></report>'), { bundle: { missed: 0, covered: 0 }, packages: {} });
});

test('parseVitest reads the line totals and rejects broken input', () => {
  assert.deepEqual(parseVitest({ total: { lines: { total: 10, covered: 8 } } }), { covered: 8, missed: 2 });
  assert.throws(() => parseVitest({}), /no total.lines/);
});

test('buildReport combines JaCoCo and Vitest results', () => {
  const report = buildReport({
    jacoco: { 'backend/core': xml(pkg('a/core', 1, 3), 1, 3) },
    vitest: { 'frontend/shell': { total: { lines: { total: 4, covered: 4 } } } },
  });
  assert.deepEqual(report, { modules: { 'backend/core': 75, 'frontend/shell': 100 }, packages: { 'backend/core:a.core': 75 } });
});

test('an empty module counts as fully covered', () => {
  assert.equal(buildReport({ jacoco: { m: xml('', 0, 0) } }).modules.m, 100);
});

const base = { modules: { m: 90 }, packages: { 'm:p': 90 } };

test('equal coverage passes', () => {
  assert.deepEqual(compare(base, base), { failures: [], warnings: [] });
});

test('a drop of exactly 2.0 points passes, 2.01 fails', () => {
  assert.deepEqual(compare({ modules: { m: 88 }, packages: {} }, base).failures, []);
  assert.match(compare({ modules: { m: 87.99 }, packages: {} }, base).failures.join(), /dropped from 90 % to 87.99 %/);
});

test('a package drop fails and a new package is accepted', () => {
  const current = { modules: { m: 90 }, packages: { 'm:p': 80, 'm:new': 10 } };
  assert.match(compare(current, base).failures.join(), /package "m:p" dropped/);
});

test('a removed package warns, a removed module fails', () => {
  const result = compare({ modules: {}, packages: {} }, base);
  assert.match(result.failures.join(), /module "m" is missing/);
  assert.match(result.warnings.join(), /package "m:p" is missing/);
});

test('a missing baseline compares as no constraints', () => {
  assert.deepEqual(compare(base, undefined), { failures: [], warnings: [] });
});

test('critical trees below 90 % fail, empty trees pass', () => {
  assert.deepEqual(criticalViolations({ m: xml(pkg('a/core', 5, 5), 5, 5) }), []);
  const bad = criticalViolations({ m: xml(pkg('a/session/store', 2, 8), 2, 8) });
  assert.match(bad.join(), /"session" has 80 % line coverage/);
  assert.deepEqual(criticalViolations({ m: xml(pkg('a/security', 1, 9), 1, 9) }), []);
});

test('critical trees aggregate across packages and modules', () => {
  const result = criticalViolations({ m1: xml(pkg('a/gateway', 0, 10) + pkg('a/gateway/x', 2, 0), 2, 10), m2: xml(pkg('b/gateway', 0, 10), 0, 10) });
  assert.deepEqual(result, []);
});

test('merge combines reports and lets later ones win', () => {
  const merged = merge({ modules: { a: 1 }, packages: { 'a:p': 1 } }, { modules: { b: 2, a: 3 }, packages: {} });
  assert.deepEqual(merged, { modules: { a: 3, b: 2 }, packages: { 'a:p': 1 } });
});
