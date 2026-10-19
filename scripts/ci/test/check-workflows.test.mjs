import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { checkDirectory, checkWorkflow } from '../check-workflows.mjs';

const SHA = 'd23441a48e516b6c34aea4fa41551a30e30af803';
const ok = `name: x\non: [push]\npermissions:\n  contents: read\njobs:\n  a:\n    steps:\n      - uses: actions/checkout@${SHA} # v6.1.0\n      - uses: ./local\n      - run: ./mvnw verify\n`;

test('a pinned workflow passes', () => {
  assert.deepEqual(checkWorkflow('ci.yml', ok), []);
});

test('a tag reference is rejected', () => {
  assert.match(checkWorkflow('ci.yml', ok.replace(SHA, 'v4')).join(), /not pinned to a commit SHA/);
});

test('a missing reference is rejected', () => {
  assert.match(checkWorkflow('ci.yml', ok.replace(`@${SHA}`, '')).join(), /not pinned/);
});

test('pull_request_target is rejected', () => {
  assert.match(checkWorkflow('ci.yml', ok.replace('[push]', '\n  pull_request_target:')).join(), /pull_request_target is forbidden/);
});

test('a comment may mention pull_request_target', () => {
  assert.deepEqual(checkWorkflow('ci.yml', `${ok}# never use pull_request_target\n`), []);
});

test('missing permissions are rejected', () => {
  assert.match(checkWorkflow('ci.yml', ok.replace('permissions:\n  contents: read\n', '')).join(), /no top-level permissions/);
});

test('integrity weakening flags are rejected', () => {
  for (const flag of ['npm ci --legacy-peer-deps', 'npm install --force', './mvnw verify -DskipTests', './mvnw -Dmaven.test.skip=true verify']) {
    assert.match(checkWorkflow('ci.yml', `${ok}      - run: ${flag}\n`).join(), /forbidden flag/);
  }
});

test('directory check reads all yaml files', () => {
  const dir = mkdtempSync(join(tmpdir(), 'wf-'));
  writeFileSync(join(dir, 'a.yml'), ok);
  writeFileSync(join(dir, 'b.yaml'), ok.replace(SHA, 'v1'));
  writeFileSync(join(dir, 'c.txt'), 'ignored');
  const problems = checkDirectory(dir);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /^b\.yaml:/);
});
