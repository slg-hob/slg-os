import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { check, render, renderTable } from '../check-versions.mjs';

const FILES = [
  'pom.xml',
  '.mvn/wrapper/maven-wrapper.properties',
  'frontend/package.json',
  'frontend/package-lock.json',
  'docs/adr/0013-versions.json',
  'docs/adr/0013-toolchain.md',
  '.github/workflows/ci.yml',
];
const repoRoot = new URL('../../../', import.meta.url).pathname;

/** Copies the real build files into a temporary tree so each test can break one of them. */
function tree(mutate = () => {}) {
  const root = mkdtempSync(join(tmpdir(), 'versions-'));
  for (const file of FILES) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    cpSync(join(repoRoot, file), join(root, file));
  }
  mutate(root);
  return root;
}
const edit = (file, from, to) => (root) => {
  const path = join(root, file);
  const text = readFileSync(path, 'utf8');
  assert.ok(text.includes(from), `${file} contains ${from}`);
  writeFileSync(path, text.replace(from, to));
};

test('the repository itself is consistent', () => {
  assert.deepEqual(check(tree()), []);
});

test('a different Java version in the pom is reported', () => {
  assert.match(check(tree(edit('pom.xml', '<java.version>25</java.version>', '<java.version>21</java.version>'))).join('\n'), /java.version: found "21"/);
});

test('a different Spring Boot parent is reported', () => {
  const problems = check(tree(edit('pom.xml', '<version>4.1.1</version>', '<version>4.1.2</version>'))).join('\n');
  assert.match(problems, /Spring Boot parent: found "4.1.2"/);
});

test('a different JaCoCo version is reported', () => {
  assert.match(check(tree(edit('pom.xml', '<jacoco.version>0.8.15<', '<jacoco.version>0.8.14<'))).join('\n'), /jacoco.version/);
});

test('a different Maven wrapper version and a missing checksum are reported', () => {
  const problems = check(tree((root) => {
    edit('.mvn/wrapper/maven-wrapper.properties', 'apache-maven-3.9.16-bin', 'apache-maven-3.9.15-bin')(root);
    edit('.mvn/wrapper/maven-wrapper.properties', 'distributionSha256Sum=', 'removed=')(root);
  })).join('\n');
  assert.match(problems, /Maven wrapper version: found "3.9.15"/);
  assert.match(problems, /no distributionSha256Sum/);
});

test('an npm version that differs from the table is reported for package.json and the lock file', () => {
  const problems = check(tree(edit('docs/adr/0013-versions.json', '"vite": "8.3.4"', '"vite": "8.3.5"'))).join('\n');
  assert.match(problems, /frontend\/package.json vite: found "8.3.4"/);
  assert.match(problems, /frontend\/package-lock.json vite: found "8.3.4"/);
});

test('a dependency missing from the table is reported', () => {
  const problems = check(tree(edit('docs/adr/0013-versions.json', '    "vite": "8.3.4",\n', ''))).join('\n');
  assert.match(problems, /vite is missing from the version table/);
});

test('an action with another SHA or without an entry is reported', () => {
  const sha = 'd23441a48e516b6c34aea4fa41551a30e30af803';
  const changed = check(tree(edit('.github/workflows/ci.yml', `checkout@${sha}`, `checkout@${'0'.repeat(40)}`))).join('\n');
  assert.match(changed, /action actions\/checkout uses 0000000/);
  const unknown = check(tree(edit('.github/workflows/ci.yml', `actions/checkout@${sha}`, `evil/thing@${sha}`))).join('\n');
  assert.match(unknown, /action evil\/thing is missing from the version table/);
});

test('a different Trivy version in a workflow is reported', () => {
  assert.match(check(tree(edit('.github/workflows/ci.yml', 'TRIVY_VERSION: v0.73.0', 'TRIVY_VERSION: v0.74.0'))).join('\n'), /TRIVY_VERSION: found "v0.74.0"/);
});

test('an outdated table in the ADR is reported and render repairs it', () => {
  const root = tree(edit('docs/adr/0013-toolchain.md', '| JDK | 25 |', '| JDK | 24 |'));
  assert.match(check(root).join('\n'), /out of date/);
  render(root);
  assert.deepEqual(check(root), []);
});

test('missing markers are reported and make render fail', () => {
  const root = tree((r) => writeFileSync(join(r, 'docs/adr/0013-toolchain.md'), '# no markers\n'));
  assert.match(check(root).join('\n'), /no versions markers/);
  assert.throws(() => render(root), /markers missing/);
});

test('the rendered table lists toolchain, npm packages, actions and Trivy', () => {
  const table = renderTable(JSON.parse(readFileSync(join(repoRoot, 'docs/adr/0013-versions.json'), 'utf8')));
  assert.match(table, /\| JDK \| 25 \|/);
  assert.match(table, /`vite` \| 8.3.4/);
  assert.match(table, /`actions\/checkout` \| v6.1.0/);
  assert.match(table, /\| Trivy \| v0.73.0 \|/);
});
