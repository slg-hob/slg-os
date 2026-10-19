import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { checkLocales, flatten, signatures } from './check-i18n.mjs';
import { parse } from '@formatjs/icu-messageformat-parser';

function workspace(files) {
  const root = mkdtempSync(join(tmpdir(), 'i18n-'));
  for (const [path, content] of Object.entries(files)) {
    const full = join(root, path);
    mkdirSync(join(full, '..'), { recursive: true });
    writeFileSync(full, typeof content === 'string' ? content : JSON.stringify(content));
  }
  return root;
}
const pair = (de, en) => ({
  'shell/src/locales/de/shell.json': de,
  'shell/src/locales/en/shell.json': en,
});

test('flatten joins nested keys with dots', () => {
  assert.deepEqual(flatten({ a: { b: 'x', c: { d: 'y' } }, e: 'z' }), { 'a.b': 'x', 'a.c.d': 'y', e: 'z' });
});

test('signatures describe arguments, plurals, selects and tags', () => {
  const sig = [...signatures(parse('{n, plural, one {# Datei} other {# Dateien}} von {name} {x, select, a {A} other {B}} <b>{when, date}</b> {v, number}'))].sort();
  assert.deepEqual(sig, ['<b>:tag', 'n:plural[one,other]', 'name:plain', 'v:number', 'when:date', 'x:select[a,other]']);
});

test('consistent locales produce no problems', () => {
  const root = workspace(pair({ a: { b: 'Hallo {name}' } }, { a: { b: 'Hello {name}' } }));
  assert.deepEqual(checkLocales(root), []);
});

test('a key missing in one language is reported', () => {
  const root = workspace(pair({ a: 'x', b: 'y' }, { a: 'x' }));
  assert.match(checkLocales(root).join('\n'), /key "b" exists only in de/);
});

test('a missing language file is reported', () => {
  const root = workspace({ 'shell/src/locales/de/shell.json': { a: 'x' } });
  assert.match(checkLocales(root).join('\n'), /missing en file/);
});

test('an unexpected language is reported', () => {
  const root = workspace({ ...pair({ a: 'x' }, { a: 'x' }), 'shell/src/locales/fr/shell.json': { a: 'x' } });
  assert.match(checkLocales(root).join('\n'), /unexpected language fr/);
});

test('different placeholder names are reported', () => {
  const root = workspace(pair({ a: 'Hallo {name}' }, { a: 'Hello {user}' }));
  assert.match(checkLocales(root).join('\n'), /different arguments/);
});

test('different plural branches are reported', () => {
  const root = workspace(pair({ a: '{n, plural, one {#} other {#}}' }, { a: '{n, plural, zero {0} one {#} other {#}}' }));
  assert.match(checkLocales(root).join('\n'), /different arguments/);
});

test('invalid ICU is reported', () => {
  const root = workspace(pair({ a: 'Hallo {name' }, { a: 'Hello {name}' }));
  assert.match(checkLocales(root).join('\n'), /not valid ICU/);
});

test('empty and non-string values are reported', () => {
  const root = workspace(pair({ a: '', b: 3 }, { a: 'x', b: 'y' }));
  const problems = checkLocales(root).join('\n');
  assert.match(problems, /key "a" must be a non-empty string/);
  assert.match(problems, /key "b" must be a non-empty string/);
});

test('invalid JSON is reported', () => {
  const root = workspace({ 'shell/src/locales/de/shell.json': '{ nope', 'shell/src/locales/en/shell.json': '{}' });
  assert.match(checkLocales(root).join('\n'), /invalid JSON/);
});

test('packages without locales are ignored', () => {
  const root = workspace({ 'contracts/src/x.ts': 'export {}' });
  assert.deepEqual(checkLocales(root), []);
});
