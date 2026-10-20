// Verification of docs/design/assets.lock (PRD-0005 VL-7 AC4, section 8 "Supply chain"):
//  - every npm asset matches the lock file (version, integrity, registry host) and declares an allowed licence
//  - every file asset exists (no symlink, inside the repository) and matches its SHA-256
//  - every package in the lock file has a licence from the allowlist (or a versioned exception for a missing field)
//  - THIRD-PARTY-NOTICES has an entry "<name> <version>" for every asset
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, realpathSync } from 'node:fs';
import { join, sep } from 'node:path';

const REGISTRY = 'https://registry.npmjs.org/';

/**
 * Parses an SPDX licence expression with the real precedence: parentheses, then AND, then OR.
 * Returns a tree ({ id } | { op: 'AND' | 'OR', left, right }) or undefined when the text is not a plain expression.
 * "WITH" exceptions and the "+" suffix are not supported and make the expression unparseable (the caller fails closed).
 */
export function parseLicenceExpression(text) {
  if (typeof text !== 'string') return undefined;
  const tokens = text.match(/\(|\)|[A-Za-z0-9.+-]+/g);
  if (!tokens || tokens.join('').length !== text.replaceAll(/\s+/g, '').length) return undefined;
  let position = 0;
  const parseOr = () => {
    let left = parseAnd();
    while (left && tokens[position]?.toUpperCase() === 'OR') {
      position += 1;
      const right = parseAnd();
      left = right ? { op: 'OR', left, right } : undefined;
    }
    return left;
  };
  const parseAnd = () => {
    let left = parseAtom();
    while (left && tokens[position]?.toUpperCase() === 'AND') {
      position += 1;
      const right = parseAtom();
      left = right ? { op: 'AND', left, right } : undefined;
    }
    return left;
  };
  const parseAtom = () => {
    const token = tokens[position];
    if (token === undefined) return undefined;
    if (token === '(') {
      position += 1;
      const inner = parseOr();
      if (!inner || tokens[position] !== ')') return undefined;
      position += 1;
      return inner;
    }
    if (token === ')' || /^(AND|OR|WITH)$/i.test(token) || token.endsWith('+')) return undefined;
    position += 1;
    return { id: token };
  };
  const tree = parseOr();
  return tree && position === tokens.length ? tree : undefined;
}

/** True when the expression is satisfiable using only allowed licences. Unparseable expressions are not allowed. */
export function licenceAllowed(expression, allowed) {
  const tree = parseLicenceExpression(expression);
  const evaluate = (node) => {
    if (node.id !== undefined) return allowed.has(node.id);
    return node.op === 'AND' ? evaluate(node.left) && evaluate(node.right) : evaluate(node.left) || evaluate(node.right);
  };
  return tree !== undefined && evaluate(tree);
}

const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex');
const escapeRegExp = (text) => text.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&');

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
  const exceptions = new Map();
  for (const e of lock.licenceExceptions ?? []) {
    if (!e.package || !e.version || !e.licence || !e.reason) problems.push(`licence exception for "${e.package}" needs package, version, licence and reason`);
    else if (!allowed.has(e.licence)) problems.push(`licence exception for "${e.package}" names a licence that is not allowed: ${e.licence}`);
    else exceptions.set(`${e.package}@${e.version}`, e);
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
      if (!/^sha512-[A-Za-z0-9+/]+=*$/.test(asset.integrity ?? '')) problems.push(`${label}: needs an sha512 integrity value`);
      if (!entry) problems.push(`${label}: not in package-lock.json`);
      else {
        if (entry.version !== asset.version) problems.push(`${label}: lock file has ${entry.version}, assets.lock says ${asset.version}`);
        if (entry.integrity !== asset.integrity) problems.push(`${label}: integrity differs from package-lock.json`);
        if (entry.license !== asset.licence) problems.push(`${label}: lock file declares licence ${entry.license}, assets.lock says ${asset.licence}`);
        if (typeof entry.resolved !== 'string' || !entry.resolved.startsWith(REGISTRY)) problems.push(`${label}: is not resolved from ${REGISTRY}`);
      }
    } else if (asset.kind === 'file') {
      problems.push(...verifyFileAsset(asset, label, repoRoot));
    } else if (asset.kind) {
      problems.push(`${label}: unknown kind "${asset.kind}"`);
    }
    if (asset.name && asset.version && !new RegExp(`^${escapeRegExp(asset.name)} ${escapeRegExp(asset.version)}\\b`, 'm').test(notices)) {
      problems.push(`${label}: THIRD-PARTY-NOTICES has no entry "${asset.name} ${asset.version}"`);
    }
  }

  for (const [path, entry] of Object.entries(packageLock.packages ?? {})) {
    if (!path.startsWith('node_modules/') || entry.link) continue; // workspace packages are our own code
    const name = path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length);
    const declared = typeof entry.license === 'string' ? entry.license : undefined;
    if (declared !== undefined) {
      if (!licenceAllowed(declared, allowed)) problems.push(`package ${name}: licence ${declared} is not on the allowlist`);
    } else if (!exceptions.has(`${name}@${entry.version}`)) {
      problems.push(`package ${name}${entry.version ? `@${entry.version}` : ''}: declares no licence (an exception needs the exact version)`);
    }
  }
  return problems;
}

function verifyFileAsset(asset, label, repoRoot) {
  const path = asset.path;
  if (!path || path.startsWith('/') || path.split('/').includes('..')) return [`${label}: path must be relative and inside the repository`];
  const full = join(repoRoot, path);
  if (!existsSync(full)) return [`${label}: file ${path} does not exist`];
  if (lstatSync(full).isSymbolicLink() || !realpathSync(full).startsWith(realpathSync(repoRoot) + sep)) {
    return [`${label}: ${path} is a symbolic link or leaves the repository`];
  }
  return sha256(readFileSync(full)) === asset.sha256 ? [] : [`${label}: SHA-256 of ${path} differs from assets.lock`];
}
