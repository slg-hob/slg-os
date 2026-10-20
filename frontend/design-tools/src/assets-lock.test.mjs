import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { licenceAllowed, parseLicenceExpression, verifyAssets } from './assets-lock.mjs';

const allowed = new Set(['MIT', 'ISC', 'Apache-2.0', 'OFL-1.1']);

describe('licence expressions', () => {
  it('builds a tree with AND binding tighter than OR', () => {
    expect(parseLicenceExpression('MIT')).toEqual({ id: 'MIT' });
    expect(parseLicenceExpression('A OR B AND C')).toEqual({ op: 'OR', left: { id: 'A' }, right: { op: 'AND', left: { id: 'B' }, right: { id: 'C' } } });
    expect(parseLicenceExpression('(A OR B) AND C')).toEqual({ op: 'AND', left: { op: 'OR', left: { id: 'A' }, right: { id: 'B' } }, right: { id: 'C' } });
  });

  it.each([
    ['MIT', true],
    ['(MIT OR GPL-3.0-only)', true],
    ['GPL-3.0-only OR MIT', true],
    ['MIT AND ISC', true],
    ['MIT AND GPL-3.0-only', false],
    ['GPL-3.0-only', false],
    ['AGPL-3.0-or-later', false],
    ['GPL-3.0-only AND (MIT OR ISC)', false],
    ['(MIT OR ISC) AND GPL-3.0-only', false],
    ['MIT OR GPL-3.0-only AND AGPL-3.0-only', true],
    ['(MIT OR GPL-3.0-only) AND ISC', true],
    ['MIT AND (ISC AND Apache-2.0)', true],
    ['mit', false],
    ['MIT WITH Classpath-exception-2.0', false],
    ['MIT+', false],
    ['MIT OR', false],
    ['(MIT', false],
    ['MIT)', false],
    ['MIT ISC', false],
    ['MIT; rm -rf', false],
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
  const outside = mkdtempSync(join(tmpdir(), 'outside-'));
  mkdirSync(join(root, 'fonts'));
  const fontBytes = Buffer.from('font bytes');
  writeFileSync(join(root, 'fonts/a.woff2'), fontBytes);
  writeFileSync(join(outside, 'secret'), fontBytes);
  symlinkSync(join(outside, 'secret'), join(root, 'fonts/link.woff2'));
  const sha = createHash('sha256').update(fontBytes).digest('hex');

  const base = () => ({
    lock: {
      allowedLicences: ['MIT', 'ISC', 'OFL-1.1'],
      licenceExceptions: [],
      assets: [
        { name: 'bootstrap', kind: 'npm', version: '5.3.8', source: 'https://registry.npmjs.org/b.tgz', integrity: 'sha512-abc=', licence: 'MIT' },
        { name: 'source-sans', kind: 'file', version: '3.0', source: 'https://example.org/a.woff2', path: 'fonts/a.woff2', sha256: sha, licence: 'OFL-1.1' },
      ],
    },
    packageLock: {
      packages: {
        '': {},
        shell: {},
        'node_modules/@slg-os/shell': { link: true, resolved: 'shell' },
        'node_modules/bootstrap': { version: '5.3.8', resolved: 'https://registry.npmjs.org/bootstrap/-/bootstrap-5.3.8.tgz', integrity: 'sha512-abc=', license: 'MIT' },
        'node_modules/dep': { version: '1.0.0', license: '(MIT OR GPL-3.0-only)' },
        'node_modules/dep/node_modules/nested': { version: '1.0.0', license: 'ISC' },
      },
    },
    repoRoot: root,
    notices: 'bootstrap 5.3.8 (MIT)\nsource-sans 3.0 (OFL)',
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
    ['an npm integrity that differs', (i) => { i.packageLock.packages['node_modules/bootstrap'].integrity = 'sha512-zzz='; }, /integrity differs/],
    ['an asset without integrity matching a lock entry without integrity', (i) => { delete i.lock.assets[0].integrity; delete i.packageLock.packages['node_modules/bootstrap'].integrity; }, /needs an sha512 integrity/],
    ['an npm package resolved from another host', (i) => { i.packageLock.packages['node_modules/bootstrap'].resolved = 'https://evil.example/bootstrap.tgz'; }, /is not resolved from https:\/\/registry\.npmjs\.org\//],
    ['a git dependency', (i) => { delete i.packageLock.packages['node_modules/bootstrap'].resolved; }, /is not resolved from/],
    ['a licence that differs from the lock file', (i) => { i.packageLock.packages['node_modules/bootstrap'].license = 'ISC'; }, /declares licence ISC, assets\.lock says MIT/],
    ['an npm asset that is not installed', (i) => { delete i.packageLock.packages['node_modules/bootstrap']; }, /not in package-lock\.json/],
    ['a file whose hash differs', (i) => { i.lock.assets[1].sha256 = '0'.repeat(64); }, /SHA-256 of fonts\/a\.woff2 differs/],
    ['a file that is missing', (i) => { i.lock.assets[1].path = 'fonts/missing.woff2'; }, /does not exist/],
    ['a symbolic link out of the repository', (i) => { i.lock.assets[1].path = 'fonts/link.woff2'; }, /symbolic link or leaves the repository/],
    ['a path that leaves the repository', (i) => { i.lock.assets[1].path = '../outside'; }, /relative and inside/],
    ['an absolute path', (i) => { i.lock.assets[1].path = '/etc/passwd'; }, /relative and inside/],
    ['an asset licence off the allowlist', (i) => { i.lock.assets[0].licence = 'GPL-3.0-only'; }, /not on the allowlist/],
    ['a source that is not https', (i) => { i.lock.assets[0].source = 'http://example.org/x'; }, /https URL/],
    ['a missing field', (i) => { delete i.lock.assets[0].version; }, /"version" is missing/],
    ['an unknown kind', (i) => { i.lock.assets[0].kind = 'cdn'; }, /unknown kind "cdn"/],
    ['an asset listed twice', (i) => { i.lock.assets.push({ ...i.lock.assets[0] }); }, /listed twice/],
    ['an asset not named in the notices', (i) => { i.notices = 'bootstrap 5.3.8'; }, /no entry "source-sans 3\.0"/],
    ['an asset whose name only appears inside other words', (i) => { i.notices = 'unbootstrap 5.3.8\nsource-sans 3.0'; }, /no entry "bootstrap 5\.3\.8"/],
    ['an asset named with another version in the notices', (i) => { i.notices = 'bootstrap 5.3.7\nsource-sans 3.0'; }, /no entry "bootstrap 5\.3\.8"/],
    ['a package with a disallowed licence', (i) => { i.packageLock.packages['node_modules/bad'] = { license: 'GPL-3.0-only' }; }, /package bad: licence GPL-3\.0-only is not on the allowlist/],
    ['a package whose compound licence hides a disallowed one', (i) => { i.packageLock.packages['node_modules/sneaky'] = { license: 'GPL-3.0-only AND (MIT OR ISC)' }; }, /package sneaky: licence/],
    ['a nested package with a disallowed licence', (i) => { i.packageLock.packages['node_modules/dep/node_modules/worse'] = { license: 'AGPL-3.0-only' }; }, /package worse: licence AGPL/],
    ['a package without a licence', (i) => { i.packageLock.packages['node_modules/silent'] = { version: '1.0.0' }; }, /package silent@1\.0\.0: declares no licence/],
    ['a package with an array licence field', (i) => { i.packageLock.packages['node_modules/old'] = { version: '1.0.0', license: ['MIT'] }; }, /package old@1\.0\.0: declares no licence/],
    ['a lock without lists', (i) => { i.lock = {}; }, /needs "allowedLicences" and "assets"/],
  ])('reports %s', (_label, mutate, message) => {
    expect(problems(mutate).join('\n')).toMatch(message);
  });

  describe('licence exceptions', () => {
    const exception = { package: 'silent', version: '1.0.0', licence: 'MIT', reason: 'LICENSE file is MIT' };
    const withException = (e, declared = {}) => problems((i) => {
      i.packageLock.packages['node_modules/silent'] = { version: '1.0.0', ...declared };
      i.lock.licenceExceptions = [e];
    });

    it('accepts a licence-less package with an exception for exactly this version', () => {
      expect(withException(exception)).toEqual([]);
    });

    it('does not carry over to a later version of the package', () => {
      const input = base();
      input.packageLock.packages['node_modules/silent'] = { version: '1.1.0' };
      input.lock.licenceExceptions = [exception];
      expect(verifyAssets(input).join('\n')).toMatch(/package silent@1\.1\.0: declares no licence/);
    });

    it('does not override a licence the package declares and the allowlist rejects', () => {
      expect(withException(exception, { license: 'GPL-3.0-only' }).join('\n')).toMatch(/package silent: licence GPL-3\.0-only is not on the allowlist/);
    });

    it('rejects an exception without reason, version or an allowed licence', () => {
      expect(withException({ package: 'silent', version: '1.0.0', licence: 'MIT' }).join('\n')).toMatch(/needs package, version, licence and reason/);
      expect(withException({ package: 'silent', licence: 'MIT', reason: 'x' }).join('\n')).toMatch(/needs package, version, licence and reason/);
      expect(withException({ ...exception, licence: 'GPL-3.0-only' }).join('\n')).toMatch(/names a licence that is not allowed/);
    });
  });
});
