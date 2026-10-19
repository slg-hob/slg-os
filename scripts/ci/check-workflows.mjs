#!/usr/bin/env node
// Workflow policy (PRD-0003 SK-2 AC4):
//  - every external action is pinned to a full commit SHA
//  - no pull_request_target trigger
//  - permissions are declared at the top of every workflow
//  - Maven and npm are never run with flags that weaken integrity (--force, --legacy-peer-deps, -Dmaven.test.skip, -DskipTests)
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SHA = /^[0-9a-f]{40}$/;
const FORBIDDEN_FLAGS = ['--legacy-peer-deps', 'npm install --force', 'npm ci --force', '-Dmaven.test.skip', '-DskipTests', '-DskipITs'];

/** Returns problems found in one workflow file's text. */
export function checkWorkflow(name, text) {
  const problems = [];
  const lines = text.split('\n');
  lines.forEach((line, index) => {
    const where = `${name}:${index + 1}`;
    const uses = /^\s*(?:-\s*)?uses:\s*([^\s#]+)/.exec(line);
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
    for (const flag of FORBIDDEN_FLAGS) if (code.includes(flag)) problems.push(`${where}: forbidden flag "${flag}"`);
  });
  if (!/^permissions:/m.test(text)) problems.push(`${name}: no top-level permissions block`);
  return problems;
}

export function checkDirectory(dir) {
  return readdirSync(dir)
    .filter((file) => /\.ya?ml$/.test(file))
    .flatMap((file) => checkWorkflow(file, readFileSync(join(dir, file), 'utf8')));
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
