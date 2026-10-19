#!/usr/bin/env node
// Documentation quality gate (no dependencies, no network):
//  - every ADR and PRD file is listed in its index and has a Status line
//  - relative markdown links resolve to existing files (and existing headings are not checked)
//  - no file larger than 5 MB, no private key material
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, dirname, resolve, relative } from 'node:path';

const root = resolve(new URL('../..', import.meta.url).pathname);
const errors = [];
const walk = (dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    if (['.git', 'node_modules', 'spikes'].includes(e.name)) return [];
    const p = join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
const files = walk(root);
const rel = (p) => relative(root, p);

// 1) indices
const checkIndex = (dir, indexFile, pattern, label) => {
  const idx = readFileSync(join(root, dir, indexFile), 'utf8');
  for (const f of readdirSync(join(root, dir)).filter((n) => pattern.test(n))) {
    if (!idx.includes(f)) errors.push(`${label} ${f} is not listed in ${dir}/${indexFile}`);
    const body = readFileSync(join(root, dir, f), 'utf8');
    if (!/^- Status:/m.test(body)) errors.push(`${dir}/${f} has no "- Status:" line`);
  }
};
checkIndex('docs/adr', 'README.md', /^\d{4}-.*\.md$/, 'ADR');
checkIndex('docs/prd', 'README.md', /^\d{4}-.*\.md$/, 'PRD');

// 2) relative links in markdown files
const linkRe = /\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
for (const f of files.filter((p) => p.endsWith('.md'))) {
  const text = readFileSync(f, 'utf8').replace(/```[\s\S]*?```/g, '').replace(/`[^`\n]*`/g, '');
  for (const m of text.matchAll(linkRe)) {
    const target = m[1];
    if (/^(https?:|mailto:|#)/.test(target)) continue;
    const path = decodeURIComponent(target.split('#')[0]);
    if (!path) continue;
    if (!existsSync(resolve(dirname(f), path))) errors.push(`${rel(f)}: broken link ${target}`);
  }
}

// 3) hygiene
for (const f of files) {
  if (statSync(f).size > 5 * 1024 * 1024) errors.push(`${rel(f)} is larger than 5 MB`);
  if (/\.(md|json|ya?ml|mjs|js|jsx|ts|tsx|sh|java|xml|properties|conf|tpl)$/.test(f)) {
    const t = readFileSync(f, 'utf8');
    if (/-----BEGIN (RSA |EC |OPENSSH |DSA |)PRIVATE KEY-----/.test(t)) errors.push(`${rel(f)} contains private key material`);
    if (/(ghp_|github_pat_)[A-Za-z0-9_]{20,}/.test(t)) errors.push(`${rel(f)} contains a GitHub token`);
  }
}

if (errors.length) {
  console.error(errors.map((e) => `- ${e}`).join('\n'));
  process.exit(1);
}
console.log(`docs check passed (${files.length} files)`);
