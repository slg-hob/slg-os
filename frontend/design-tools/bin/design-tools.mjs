#!/usr/bin/env node
// Entry point without logic of its own: resolves the repository paths and runs the command.
import { fileURLToPath } from 'node:url';
import { run } from '../src/cli.mjs';

const repoRoot = fileURLToPath(new URL('../../../', import.meta.url));
const code = run(process.argv.slice(2), {
  designDir: `${repoRoot}design`,
  packageDir: `${repoRoot}frontend/design-system`,
  repoRoot,
  platformSourceDirs: [`${repoRoot}frontend/shell/src`, `${repoRoot}frontend/design-system/src`],
  lockFile: `${repoRoot}docs/design/assets.lock`,
  packageLockFile: `${repoRoot}frontend/package-lock.json`,
  noticesFile: `${repoRoot}THIRD-PARTY-NOTICES`,
});
process.exit(code);
