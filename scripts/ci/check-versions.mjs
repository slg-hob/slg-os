#!/usr/bin/env node
// Version gate (PRD-0003 SK-1 AC2): docs/adr/0013-versions.json is the single machine-readable table of pinned versions.
//  - it must match pom.xml, the Maven wrapper, frontend/package.json (exact versions), frontend/package-lock.json and the workflows
//  - the table in docs/adr/0013-toolchain.md is rendered from it; "--render" rewrites the table, the default run fails on a difference
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const START = '<!-- versions:start -->';
const END = '<!-- versions:end -->';

const read = (root, file) => readFileSync(join(root, file), 'utf8');
const capture = (text, regex) => new RegExp(regex).exec(text)?.[1];

/** Renders the markdown table that the ADR embeds. */
export function renderTable(versions) {
  const rows = [['Component', 'Version', 'Where it is pinned']];
  const t = versions.toolchain;
  rows.push(['JDK', t.jdk, '`pom.xml` (`java.version`, `maven.compiler.release`), CI `setup-java`']);
  rows.push(['Spring Boot', t.springBoot, '`pom.xml` parent']);
  rows.push(['Maven', t.maven, '`.mvn/wrapper/maven-wrapper.properties` with SHA-256']);
  rows.push(['JaCoCo', t.jacoco, '`pom.xml` (`jacoco.version`)']);
  rows.push(['Node.js', t.node, '`frontend/package.json` (`engines`)']);
  for (const [name, version] of Object.entries(versions.npm)) rows.push([`\`${name}\``, version, '`frontend/package.json` and lock file']);
  for (const [name, { tag, sha }] of Object.entries(versions.actions)) rows.push([`\`${name}\``, `${tag} (\`${sha.slice(0, 7)}\`)`, 'workflows (commit SHA)']);
  rows.push(['Trivy', versions.tools.trivy, 'CI security job']);
  const line = (cells) => `| ${cells.join(' | ')} |`;
  return [line(rows[0]), line(['---', '---', '---']), ...rows.slice(1).map(line)].join('\n');
}

/** Returns problems found when comparing the version table with the files of the repository at root. */
export function check(root) {
  const problems = [];
  const versions = JSON.parse(read(root, 'docs/adr/0013-versions.json'));
  const t = versions.toolchain;
  const pom = read(root, 'pom.xml');
  const expectEqual = (label, actual, expected) => {
    if (actual !== expected) problems.push(`${label}: found ${JSON.stringify(actual)}, table says ${JSON.stringify(expected)}`);
  };
  expectEqual('pom.xml java.version', capture(pom, '<java\\.version>([^<]+)</java\\.version>'), t.jdk);
  expectEqual('pom.xml maven.compiler.release', capture(pom, '<maven\\.compiler\\.release>([^<]+)<'), t.jdk);
  expectEqual('pom.xml Spring Boot parent', capture(pom, '<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>'), t.springBoot);
  expectEqual('pom.xml jacoco.version', capture(pom, '<jacoco\\.version>([^<]+)<'), t.jacoco);
  const wrapper = read(root, '.mvn/wrapper/maven-wrapper.properties');
  expectEqual('Maven wrapper version', capture(wrapper, 'apache-maven-([0-9.]+)-bin'), t.maven);
  if (!/^distributionSha256Sum=[0-9a-f]{64}$/m.test(wrapper)) problems.push('Maven wrapper has no distributionSha256Sum');
  const enforcerMaven = capture(pom, '<requireMavenVersion><version>\\[([0-9.]+),');
  expectEqual('pom.xml enforced Maven minimum', enforcerMaven, t.maven);
  const pkg = JSON.parse(read(root, 'frontend/package.json'));
  expectEqual('frontend/package.json engines.node', pkg.engines?.node, t.node);
  const lock = JSON.parse(read(root, 'frontend/package-lock.json'));
  for (const [name, version] of Object.entries(versions.npm)) {
    expectEqual(`frontend/package.json ${name}`, pkg.devDependencies?.[name], version);
    expectEqual(`frontend/package-lock.json ${name}`, lock.packages?.[`node_modules/${name}`]?.version, version);
  }
  for (const name of Object.keys(pkg.devDependencies ?? {})) {
    if (!(name in versions.npm)) problems.push(`frontend/package.json ${name} is missing from the version table`);
  }
  const workflowDir = join(root, '.github/workflows');
  for (const file of readdirSync(workflowDir).filter((f) => /\.ya?ml$/.test(f))) {
    const text = read(root, `.github/workflows/${file}`);
    for (const match of text.matchAll(/uses:\s*([\w.-]+\/[\w.-]+)(?:\/[\w./-]+)?@([0-9a-f]{40})/g)) {
      const entry = versions.actions[match[1]];
      if (!entry) problems.push(`${file}: action ${match[1]} is missing from the version table`);
      else if (entry.sha !== match[2]) problems.push(`${file}: action ${match[1]} uses ${match[2].slice(0, 7)}, table says ${entry.sha.slice(0, 7)}`);
    }
    for (const match of text.matchAll(/TRIVY_VERSION:\s*(\S+)/g)) expectEqual(`${file} TRIVY_VERSION`, match[1], versions.tools.trivy);
  }
  const adr = read(root, 'docs/adr/0013-toolchain.md');
  const start = adr.indexOf(START);
  const end = adr.indexOf(END);
  if (start < 0 || end < start) problems.push('docs/adr/0013-toolchain.md has no versions markers');
  else if (adr.slice(start + START.length, end).trim() !== renderTable(versions)) problems.push('the table in docs/adr/0013-toolchain.md is out of date (run: node scripts/ci/check-versions.mjs --render)');
  return problems;
}

export function render(root) {
  const versions = JSON.parse(read(root, 'docs/adr/0013-versions.json'));
  const file = join(root, 'docs/adr/0013-toolchain.md');
  const adr = readFileSync(file, 'utf8');
  const start = adr.indexOf(START);
  const end = adr.indexOf(END);
  if (start < 0 || end < start) throw new Error('markers missing');
  writeFileSync(file, `${adr.slice(0, start + START.length)}\n${renderTable(versions)}\n${adr.slice(end)}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve('.');
  if (process.argv.includes('--render')) {
    render(root);
    console.log('table rendered');
  } else {
    const problems = check(root);
    if (problems.length > 0) {
      console.error(problems.map((p) => `- ${p}`).join('\n'));
      process.exit(1);
    }
    console.log('version table matches the build files');
  }
}
