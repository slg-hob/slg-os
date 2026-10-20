import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { test } from 'node:test';
import { runFaults } from '../selftest.mjs';

test('detected faults produce no problems', () => {
  assert.deepEqual(runFaults([{ name: 'a', command: 'x' }], () => ({ exitCode: 1 })), []);
});

test('an undetected fault is reported', () => {
  assert.match(runFaults([{ name: 'a', command: 'x' }], () => ({ exitCode: 0 })).join(), /"a" was NOT detected/);
});

test('a patch that does not apply is reported', () => {
  assert.match(runFaults([{ name: 'a', command: 'x' }], () => ({ error: 'bad patch' })).join(), /could not be prepared: bad patch/);
});

test('duplicate names and missing commands are reported', () => {
  const problems = runFaults([{ name: 'a', command: 'x' }, { name: 'a' }], () => ({ exitCode: 1 })).join();
  assert.match(problems, /duplicate fault name "a"/);
  assert.match(problems, /has no command/);
});

test('the fault list is well formed and every patch file exists', () => {
  const faults = JSON.parse(readFileSync(new URL('../../../ci/selftest/faults.json', import.meta.url), 'utf8'));
  assert.ok(faults.length >= 8, 'at least the faults named in PRD-0003 SK-2 AC3');
  for (const fault of faults) {
    assert.ok(fault.name && fault.command, `${fault.name} needs name and command`);
    if (fault.patch) assert.ok(existsSync(new URL(`../../../ci/selftest/patches/${fault.patch}`, import.meta.url)), `${fault.patch} exists`);
  }
});
