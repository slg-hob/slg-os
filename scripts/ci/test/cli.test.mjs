// Tests of the command line entry points, run as subprocesses against temporary directories.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';

const scripts = new URL('..', import.meta.url).pathname;
const run = (script, args = [], options = {}) =>
  spawnSync(process.execPath, [join(scripts, script), ...args], { encoding: 'utf8', ...options, env: { ...process.env, ...options.env } });

function write(root, files) {
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), typeof content === 'string' ? content : JSON.stringify(content));
  }
  return root;
}
const temp = () => mkdtempSync(join(tmpdir(), 'cli-'));

test('gate passes with all jobs successful and fails otherwise', () => {
  const ok = run('gate.mjs', [], { env: { NEEDS_JSON: JSON.stringify({ a: { result: 'success' } }), REQUIRED_JOBS: 'a' } });
  assert.equal(ok.status, 0);
  const skipped = run('gate.mjs', [], { env: { NEEDS_JSON: JSON.stringify({ a: { result: 'skipped' } }) } });
  assert.equal(skipped.status, 1);
  assert.match(skipped.stderr, /"a" finished with "skipped"/);
  const missing = run('gate.mjs', [], { env: { NEEDS_JSON: '', REQUIRED_JOBS: '' } });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /NEEDS_JSON/);
});

const jacoco = '<report name="m"><package name="a/core"><counter type="LINE" missed="1" covered="9"/></package><counter type="LINE" missed="1" covered="9"/></report>';
const summary = { total: { lines: { total: 10, covered: 10 } } };

test('coverage report, merge and compare work end to end', () => {
  const root = write(temp(), {
    'backend/core/target/site/jacoco/jacoco.xml': jacoco,
    'frontend/shell/coverage/coverage-summary.json': summary,
  });
  const report = run('coverage.mjs', ['report', join(root, 'report.json')], { cwd: root });
  assert.equal(report.status, 0, report.stderr);
  const data = JSON.parse(readFileSync(join(root, 'report.json'), 'utf8'));
  assert.equal(data.modules['backend/core'], 90);
  assert.equal(data.modules['frontend/shell'], 100);

  write(root, { 'other.json': { modules: { extra: 50 }, packages: {} } });
  const merged = run('coverage.mjs', ['merge', join(root, 'merged.json'), join(root, 'report.json'), join(root, 'other.json')], { cwd: root });
  assert.equal(merged.status, 0, merged.stderr);
  assert.equal(JSON.parse(readFileSync(join(root, 'merged.json'), 'utf8')).modules.extra, 50);

  const same = run('coverage.mjs', ['compare', join(root, 'report.json'), join(root, 'report.json')], { cwd: root });
  assert.equal(same.status, 0);
  write(root, { 'worse.json': { modules: { 'backend/core': 50 }, packages: {} } });
  const worse = run('coverage.mjs', ['compare', join(root, 'worse.json'), join(root, 'report.json')], { cwd: root });
  assert.equal(worse.status, 1);
  assert.match(worse.stderr, /dropped from 90 % to 50 %/);
});

test('coverage report fails without data and an unknown command prints usage', () => {
  const empty = run('coverage.mjs', ['report', 'x.json'], { cwd: temp() });
  assert.notEqual(empty.status, 0);
  const usage = run('coverage.mjs', ['nonsense']);
  assert.equal(usage.status, 2);
  assert.match(usage.stderr, /usage/);
});

test('coverage critical passes on empty trees and fails below the minimum', () => {
  const clean = write(temp(), { 'backend/core/target/site/jacoco/jacoco.xml': jacoco });
  assert.equal(run('coverage.mjs', ['critical'], { cwd: clean }).status, 0);
  const bad = write(temp(), {
    'backend/core/target/site/jacoco/jacoco.xml': jacoco.replaceAll('a/core', 'a/security').replace('missed="1" covered="9"', 'missed="5" covered="5"'),
  });
  const result = run('coverage.mjs', ['critical'], { cwd: bad });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /"security"/);
});

test('check-workflows reports problems of a directory and passes a clean one', () => {
  const clean = write(temp(), { 'ci.yml': 'name: x\npermissions:\n  contents: read\njobs: {}\n' });
  assert.equal(run('check-workflows.mjs', [clean]).status, 0);
  const dirty = write(temp(), { 'ci.yml': 'name: x\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n' });
  const result = run('check-workflows.mjs', [dirty]);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /not pinned/);
});

test('check-versions renders and checks in a repository copy', () => {
  const repo = new URL('../../..', import.meta.url).pathname;
  const root = temp();
  for (const file of ['pom.xml', '.mvn/wrapper/maven-wrapper.properties', 'frontend/package.json', 'frontend/package-lock.json', 'docs/adr/0013-versions.json', 'docs/adr/0013-toolchain.md', '.github/workflows/ci.yml']) {
    write(root, { [file]: readFileSync(join(repo, file), 'utf8') });
  }
  assert.equal(run('check-versions.mjs', [], { cwd: root }).status, 0);
  writeFileSync(join(root, 'docs/adr/0013-toolchain.md'), readFileSync(join(root, 'docs/adr/0013-toolchain.md'), 'utf8').replace('| JDK | 25 |', '| JDK | 9 |'));
  const stale = run('check-versions.mjs', [], { cwd: root });
  assert.equal(stale.status, 1);
  assert.match(stale.stderr, /out of date/);
  assert.equal(run('check-versions.mjs', ['--render'], { cwd: root }).status, 0);
  assert.equal(run('check-versions.mjs', [], { cwd: root }).status, 0);
});

function gitRepo() {
  const root = write(temp(), {
    'tracked.txt': 'ok\n',
    'ci/selftest/faults.json': [
      { name: 'detected', patch: 'break.patch', command: 'grep -q ok tracked.txt && exit 1; exit 0' },
      { name: 'always-fails', command: 'exit 3' },
    ],
    'ci/selftest/patches/break.patch': 'diff --git a/tracked.txt b/tracked.txt\n--- a/tracked.txt\n+++ b/tracked.txt\n@@ -1 +1 @@\n-ok\n+bad\n',
  });
  const git = (...args) => spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  git('init', '-q');
  git('add', '-A');
  git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', 'init');
  return { root, git };
}

test('selftest refuses to run outside CI', () => {
  const { root } = gitRepo();
  const result = run('selftest.mjs', [], { cwd: root, env: { CI: 'false' } });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /refusing to run/);
});

test('selftest applies patches, restores the tree and reports undetected faults', () => {
  const { root } = gitRepo();
  const detected = run('selftest.mjs', ['always-fails'], { cwd: root, env: { CI: 'true' } });
  assert.equal(detected.status, 0, detected.stderr);
  assert.match(detected.stdout, /fault "always-fails" is detected/);
  // the patch fault passes its command (exit 0), which means "not detected": the tree must still be restored afterwards
  const undetected = run('selftest.mjs', ['detected'], { cwd: root, env: { CI: 'true' } });
  assert.equal(undetected.status, 1);
  assert.match(undetected.stderr, /was NOT detected|could not be prepared/);
  assert.equal(readFileSync(join(root, 'tracked.txt'), 'utf8'), 'ok\n');
});
