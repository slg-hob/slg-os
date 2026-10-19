#!/usr/bin/env node
// Gate self-test (PRD-0003 SK-2 AC3): applies each deliberate fault from ci/selftest/faults.json to a checkout and
// asserts that the matching check fails. Destructive for the working tree, so it only runs on CI (CI=true).
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Runs all faults with the given executor. The executor gets (fault) and returns { applied, exitCode }; returns problems.
 * A fault is detected when its command exits non-zero.
 */
export function runFaults(faults, execute) {
  const problems = [];
  const names = new Set();
  for (const fault of faults) {
    if (names.has(fault.name)) problems.push(`duplicate fault name "${fault.name}"`);
    names.add(fault.name);
    if (!fault.command) {
      problems.push(`fault "${fault.name}" has no command`);
      continue;
    }
    const result = execute(fault);
    if (result.error) problems.push(`fault "${fault.name}" could not be prepared: ${result.error}`);
    else if (result.exitCode === 0) problems.push(`fault "${fault.name}" was NOT detected (command exited with 0)`);
    else console.log(`ok: fault "${fault.name}" is detected (exit ${result.exitCode})`);
  }
  return problems;
}

function shell(command, env = {}) {
  return spawnSync('bash', ['-c', command], { stdio: 'inherit', env: { ...process.env, ...env } });
}

function realExecutor(fault) {
  try {
    if (fault.patch) {
      const apply = spawnSync('git', ['apply', '--whitespace=nowarn', `ci/selftest/patches/${fault.patch}`], { stdio: 'inherit' });
      if (apply.status !== 0) return { error: `patch ${fault.patch} does not apply` };
    }
    const run = shell(fault.command, fault.env);
    return { exitCode: run.status ?? 1 };
  } finally {
    spawnSync('git', ['checkout', '--', '.'], { stdio: 'inherit' });
    spawnSync('git', ['clean', '-fdq'], { stdio: 'inherit' });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.env.CI !== 'true') {
    console.error('refusing to run: the self-test resets the working tree and only runs on CI (set CI=true if you know what you do)');
    process.exit(2);
  }
  const faults = JSON.parse(readFileSync('ci/selftest/faults.json', 'utf8'));
  const only = process.argv[2];
  const problems = runFaults(only ? faults.filter((f) => f.name === only) : faults, realExecutor);
  if (problems.length > 0) {
    console.error(problems.map((p) => `- ${p}`).join('\n'));
    process.exit(1);
  }
  console.log('all faults are detected');
}
