// Verification of docs/design/assets.lock (PRD-0005 VL-7 AC4, section 8 "Supply chain"):
//  - every npm asset matches the lock file (version and integrity) and declares an allowed licence
//  - every file asset exists and matches its SHA-256
//  - every package in the lock file has a licence from the allowlist (or an exception with a reason)
//  - THIRD-PARTY-NOTICES names every asset
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/** Splits an SPDX expression into alternatives (OR) of required licences (AND). Parentheses are flattened. */
export function parseLicenceExpression(expression) {
  return expression
    .replaceAll(/[()]/g, ' ')
    .split(/\s+OR\s+/i)
    .map((alternative) => alternative.split(/\s+AND\s+/i).map((id) => id.trim()).filter(Boolean));
}

/** True when every licence of at least one alternative is allowed. */
export function licenceAllowed(expression, allowed) {
  if (typeof expression !== 'string' || expression.trim() === '') return false;
  return parseLicenceExpression(expression).some((alternative) => alternative.length > 0 && alternative.every((id) => allowed.has(id)));
}

const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex');

/**
 * @param {object} options
 * @param {object} options.lock parsed docs/design/assets.lock
 * @param {object} options.packageLock parsed frontend/package-lock.json
 * @param {string} options.repoRoot directory that file assets are relative to
 * @param {string} options.notices text of THIRD-PARTY-NOTICES
 * @returns {string[]} problems
 */
export function verifyAssets({ lock, packageLock, repoRoot, notices }) {
  const problems = [];
  if (!Array.isArray(lock.allowedLicences) || !Array.isArray(lock.assets)) return ['assets.lock needs "allowedLicences" and "assets" lists'];
  const allowed = new Set(lock.allowedLicences);
  const exceptions = new Map((lock.licenceExceptions ?? []).map((e) => [e.package, e]));
  for (const e of exceptions.values()) {
    if (!e.licence || !e.reason) problems.push(`licence exception for "${e.package}" needs licence and reason`);
    else if (!allowed.has(e.licence)) problems.push(`licence exception for "${e.package}" names a licence that is not allowed: ${e.licence}`);
  }

  const names = new Set();
  for (const asset of lock.assets) {
    const label = `asset "${asset.name}"`;
    if (names.has(asset.name)) problems.push(`${label} is listed twice`);
    names.add(asset.name);
    for (const field of ['name', 'kind', 'version', 'source', 'licence']) {
      if (!asset[field]) problems.push(`${label}: "${field}" is missing`);
    }
    if (asset.licence && !allowed.has(asset.licence)) problems.push(`${label}: licence ${asset.licence} is not on the allowlist`);
    if (asset.source && !/^https:\/\//.test(asset.source)) problems.push(`${label}: source must be an https URL`);
    if (asset.kind === 'npm') {
      const entry = packageLock.packages?.[`node_modules/${asset.name}`];
      if (!entry) problems.push(`${label}: not in package-lock.json`);
      else {
        if (entry.version !== asset.version) problems.push(`${label}: lock file has ${entry.version}, assets.lock says ${asset.version}`);
        if (entry.integrity !== asset.integrity) problems.push(`${label}: integrity differs from package-lock.json`);
        if (entry.license !== asset.licence) problems.push(`${label}: lock file declares licence ${entry.license}, assets.lock says ${asset.licence}`);
      }
    } else if (asset.kind === 'file') {
      const path = asset.path;
      if (!path || path.startsWith('/') || path.split('/').includes('..')) problems.push(`${label}: path must be relative and inside the repository`);
      else if (!existsSync(join(repoRoot, path))) problems.push(`${label}: file ${path} does not exist`);
      else if (sha256(readFileSync(join(repoRoot, path))) !== asset.sha256) problems.push(`${label}: SHA-256 of ${path} differs from assets.lock`);
    } else if (asset.kind) {
      problems.push(`${label}: unknown kind "${asset.kind}"`);
    }
    if (asset.name && !notices.includes(asset.name)) problems.push(`${label}: not named in THIRD-PARTY-NOTICES`);
  }

  for (const [path, entry] of Object.entries(packageLock.packages ?? {})) {
    if (!path.startsWith('node_modules/') || entry.link) continue; // workspace packages are our own code
    const name = path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length);
    const declared = typeof entry.license === 'string' ? entry.license : undefined;
    if (declared && licenceAllowed(declared, allowed)) continue;
    const exception = exceptions.get(name);
    if (exception) continue;
    problems.push(declared ? `package ${name}: licence ${declared} is not on the allowlist` : `package ${name}: declares no licence`);
  }
  return problems;
}
