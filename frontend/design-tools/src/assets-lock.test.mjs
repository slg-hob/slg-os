import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { licenceAllowed, parseLicenceExpression, verifyAssets } from './assets-lock.mjs';

const allowed = new Set(['MIT', 'ISC', 'Apache-2.0', 'OFL-1.1']);

describe('licence expressions', () => {
  it('splits OR alternatives and AND requirements', () => {
    expect(parseLicenceExpression('(MIT OR Apache-2.0)')).toEqual([['MIT'], ['Apache-2.0']]);
    expect(parseLicenceExpression('MIT AND ISC')).toEqual([['MIT', 'ISC']]);
  });

  it.each([
    ['MIT', true],
    ['(MIT OR GPL-3.0-only)', true],
    ['GPL-3.0-only OR MIT', true],
    ['MIT AND ISC', true],
    ['MIT AND GPL-3.0-only', false],
    ['GPL-3.0-only', false],
    ['AGPL-3.0-or-later', false],
    ['', false],
    ['  ', false],
    [undefined, false],
    [42, false],
  ])('judges %j as allowed=%s', (expression, expected) => {
    expect(licenceAllowed(expression, allowed)).toBe(expected);
  });
});

describe('verifyAssets', () => {
  const root = mkdtempSync(join(tmpdir(), 'assets-'));
  mkdirSync(join(root, 'fonts'));
  const fontBytes = Buffer.from('font bytes');
  writeFileSync(join(root, 'fonts/a.woff2'), fontBytes);
  const sha = createHash('sha256').update(fontBytes).digest('hex');

  const base = () => ({
    lock: {
      allowedLicences: ['MIT', 'ISC', 'OFL-1.1'],
      licenceExceptions: [],
      assets: [
        { name: 'bootstrap', kind: 'npm', version: '5.3.8', source: 'https://registry.npmjs.org/b.tgz', integrity: 'sha512-abc', licence: 'MIT' },
        { name: 'source-sans', kind: 'file', version: '3.0', source: 'https://example.org/a.woff2', path: 'fonts/a.woff2', sha256: sha, licence: 'OFL-1.1' },
      ],
    },
    packageLock: {
      packages: {
        '': {},
        shell: {},
        'node_modules/@slg-os/shell': { link: true, resolved: 'shell' },
        'node_modules/bootstrap': { version: '5.3.8', integrity: 'sha512-abc', license: 'MIT' },
        'node_modules/dep': { version: '1.0.0', license: '(MIT OR GPL-3.0-only)' },
        'node_modules/dep/node_modules/nested': { version: '1.0.0', license: 'ISC' },
      },
    },
    repoRoot: root,
    notices: 'bootstrap (MIT)\nsource-sans (OFL)',
  });
  const problems = (mutate) => {
    const input = base();
    mutate(input);
    return verifyAssets(input);
  };

  it('accepts a consistent lock', () => {
    expect(verifyAssets(base())).toEqual([]);
  });

  it.each([
    ['an npm version that differs', (i) => { i.packageLock.packages['node_modules/bootstrap'].version = '5.3.9'; }, /lock file has 5\.3\.9, assets\.lock says 5\.3\.8/],
    ['an npm integrity that differs', (i) => { i.packageLock.packages['node_modules/bootstrap'].integrity = 'sha512-zzz'; }, /integrity differs/],
    ['a licence that differs from the lock file', (i) => { i.packageLock.packages['node_modules/bootstrap'].license = 'ISC'; }, /declares licence ISC, assets\.lock says MIT/],
    ['an npm asset that is not installed', (i) => { delete i.packageLock.packages['node_modules/bootstrap']; }, /not in package-lock\.json/],
    ['a file whose hash differs', (i) => { i.lock.assets[1].sha256 = '0'.repeat(64); }, /SHA-256 of fonts\/a\.woff2 differs/],
    ['a file that is missing', (i) => { i.lock.assets[1].path = 'fonts/missing.woff2'; }, /does not exist/],
    ['a path that leaves the repository', (i) => { i.lock.assets[1].path = '../outside'; }, /relative and inside/],
    ['an absolute path', (i) => { i.lock.assets[1].path = '/etc/passwd'; }, /relative and inside/],
    ['an asset licence off the allowlist', (i) => { i.lock.assets[0].licence = 'GPL-3.0-only'; }, /not on the allowlist/],
    ['a source that is not https', (i) => { i.lock.assets[0].source = 'http://example.org/x'; }, /https URL/],
    ['a missing field', (i) => { delete i.lock.assets[0].version; }, /"version" is missing/],
    ['an unknown kind', (i) => { i.lock.assets[0].kind = 'cdn'; }, /unknown kind "cdn"/],
    ['an asset listed twice', (i) => { i.lock.assets.push({ ...i.lock.assets[0] }); }, /listed twice/],
    ['an asset not named in the notices', (i) => { i.notices = 'bootstrap'; }, /"source-sans": not named in THIRD-PARTY-NOTICES|source-sans.*not named/],
    ['a package with a disallowed licence', (i) => { i.packageLock.packages['node_modules/bad'] = { license: 'GPL-3.0-only' }; }, /package bad: licence GPL-3\.0-only is not on the allowlist/],
    ['a nested package with a disallowed licence', (i) => { i.packageLock.packages['node_modules/dep/node_modules/worse'] = { license: 'AGPL-3.0-only' }; }, /package worse: licence AGPL/],
    ['a package without a licence', (i) => { i.packageLock.packages['node_modules/silent'] = { version: '1.0.0' }; }, /package silent: declares no licence/],
    ['a package with an array licence field', (i) => { i.packageLock.packages['node_modules/old'] = { license: ['MIT'] }; }, /package old: declares no licence/],
    ['a lock without lists', (i) => { i.lock = {}; }, /needs "allowedLicences" and "assets"/],
  ])('reports %s', (_label, mutate, message) => {
    expect(problems(mutate).join('\n')).toMatch(message);
  });

  it('accepts a licence-less package with a documented exception and rejects a careless one', () => {
    const exception = { package: 'silent', licence: 'MIT', reason: 'LICENSE file is MIT' };
    const withException = (e) => problems((i) => {
      i.packageLock.packages['node_modules/silent'] = { version: '1.0.0' };
      i.lock.licenceExceptions = [e];
    });
    expect(withException(exception)).toEqual([]);
    expect(withException({ package: 'silent', licence: 'MIT' }).join('\n')).toMatch(/needs licence and reason/);
    expect(withException({ ...exception, licence: 'GPL-3.0-only' }).join('\n')).toMatch(/names a licence that is not allowed/);
  });
});
