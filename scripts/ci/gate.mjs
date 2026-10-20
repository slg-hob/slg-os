#!/usr/bin/env node
// Gate decision (PRD-0003 SK-2 AC1a): every required job must have the result "success".
// A skipped, cancelled or missing job fails the gate, so a job cannot be bypassed by a path filter or a failed condition.
// Input: JSON of the "needs" context in the environment variable NEEDS_JSON.
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

/** Returns the list of problems; empty means the gate passes. */
export function evaluate(needs, required = []) {
  const problems = [];
  const names = new Set([...Object.keys(needs ?? {}), ...required]);
  if (names.size === 0) problems.push('no jobs to evaluate');
  for (const name of [...names].sort()) {
    const result = needs?.[name]?.result;
    if (result !== 'success') problems.push(`job "${name}" finished with "${result ?? 'missing'}" instead of "success"`);
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let needs;
  try {
    needs = JSON.parse(process.env.NEEDS_JSON ?? '');
  } catch {
    console.error('NEEDS_JSON is missing or not valid JSON');
    process.exit(1);
  }
  const required = (process.env.REQUIRED_JOBS ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const problems = evaluate(needs, required);
  if (problems.length > 0) {
    console.error(problems.map((p) => `- ${p}`).join('\n'));
    process.exit(1);
  }
  console.log(`gate passed (${Object.keys(needs).length} jobs)`);
}
