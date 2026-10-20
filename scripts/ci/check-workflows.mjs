#!/usr/bin/env node
// Workflow policy (PRD-0003 SK-2 AC4):
//  - every external action is pinned to a full commit SHA
//  - no pull_request_target trigger
//  - permissions are declared at the top of every workflow
//  - Maven and npm are never run with flags that weaken integrity (--force, --legacy-peer-deps, -Dmaven.test.skip, -DskipTests)
//  - no workflow_run trigger, no continue-on-error, no self-hosted runners, no write-all
//  - write permissions only in the jobs listed in WRITE_ALLOWED
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SHA = /^[0-9a-f]{40}$/;
const FORBIDDEN_FLAGS = ['--legacy-peer-deps', 'npm install --force', 'npm ci --force', '-Dmaven.test.skip', '-DskipTests', '-DskipITs', '-Dmaven.test.failure.ignore', '--no-verify'];
const WRITE_ALLOWED = new Set(['coverage-baseline']);

/** Returns problems found in one workflow file's text. */
export function checkWorkflow(name, text) {
  const problems = [];
  const lines = text.split('\n');
  let job = null;
  lines.forEach((line, index) => {
    const where = `${name}:${index + 1}`;
    const jobStart = /^ {2}([\w-]+):\s*$/.exec(line);
    if (jobStart) job = jobStart[1];
    else if (/^\S/.test(line)) job = null;
    const uses = /\buses["']?\s*:\s*["']?([^\s,#}"']+)/.exec(line.replace(/#.*/, ''));
    if (uses) {
      const [target] = uses.slice(1);
      if (!target.startsWith('./')) {
        const [, ref] = target.split('@');
        if (!ref || !SHA.test(ref)) problems.push(`${where}: action "${target}" is not pinned to a commit SHA`);
      }
    }
    if (/^\s*pull_request_target\s*:/.test(line) || /\bpull_request_target\b/.test(line.replace(/#.*/, ''))) {
      problems.push(`${where}: pull_request_target is forbidden`);
    }
    const code = line.replace(/#.*/, '');
    if (/\bworkflow_run\b/.test(code)) problems.push(`${where}: workflow_run is forbidden`);
    if (/continue-on-error\s*:\s*(?!false\b)\S/.test(code)) problems.push(`${where}: continue-on-error would hide a failing job from the gate`);
    if (/\bself-hosted\b/.test(code)) problems.push(`${where}: only GitHub-hosted runners are allowed`);
    if (/permissions\s*:\s*write-all/.test(code)) problems.push(`${where}: write-all permissions are forbidden`);
    if (/^\s+[\w-]+\s*:\s*write\s*$/.test(code) && !(job && WRITE_ALLOWED.has(job))) {
      problems.push(`${where}: write permission outside ${[...WRITE_ALLOWED].join(', ')}`);
    }
    for (const flag of FORBIDDEN_FLAGS) if (code.includes(flag)) problems.push(`${where}: forbidden flag "${flag}"`);
  });
  if (!/^permissions:/m.test(text)) problems.push(`${name}: no top-level permissions block`);
  return problems;
}

/** Every job of ci.yml except the gate itself and the main-only baseline writer must be listed in the gate's needs. */
export function checkGateNeeds(name, text) {
  const jobsAt = text.search(/^jobs:\s*$/m);
  if (jobsAt < 0) return [];
  const jobs = [...text.slice(jobsAt).matchAll(/^ {2}([\w-]+):\s*$/gm)].map((m) => m[1]);
  if (!jobs.includes('gate')) return [`${name}: no job named "gate"`];
  const block = text.slice(text.search(/^ {2}gate:\s*$/m));
  const needs = /^\s+needs:\s*\[([^\]]*)\]/m.exec(block)?.[1].split(',').map((n) => n.trim()) ?? [];
  return jobs
    .filter((job) => job !== 'gate' && job !== 'coverage-baseline' && !needs.includes(job))
    .map((job) => `${name}: job "${job}" is not in the needs of the gate`);
}

export function checkDirectory(dir) {
  return readdirSync(dir)
    .filter((file) => /\.ya?ml$/.test(file))
    .flatMap((file) => {
      const text = readFileSync(join(dir, file), 'utf8');
      return [...checkWorkflow(file, text), ...(file === 'ci.yml' ? checkGateNeeds(file, text) : [])];
    });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const dir = resolve(process.argv[2] ?? '.github/workflows');
  const problems = checkDirectory(dir);
  if (problems.length > 0) {
    console.error(problems.map((p) => `- ${p}`).join('\n'));
    process.exit(1);
  }
  console.log('workflow policy check passed');
}
