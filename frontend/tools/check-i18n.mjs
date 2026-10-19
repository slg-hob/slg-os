#!/usr/bin/env node
// i18n gate (PRD-0003 SK-2 AC1, PRD-0001 NFR-3, UI-26):
//  - every locale file exists in German and English with identical keys
//  - every message is valid ICU MessageFormat
//  - argument names and kinds (plain, number, date, plural, select) and plural/select branches match between languages
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse, TYPE } from '@formatjs/icu-messageformat-parser';

const LANGUAGES = ['de', 'en'];

/** Flattens nested objects to dotted keys; arrays and non-strings are reported as errors by the caller. */
export function flatten(value, prefix = '') {
  const out = {};
  for (const [key, child] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (child !== null && typeof child === 'object' && !Array.isArray(child)) Object.assign(out, flatten(child, path));
    else out[path] = child;
  }
  return out;
}

/** Collects argument signatures such as "name:plain" or "count:plural[one,other]" from an ICU AST. */
export function signatures(ast, into = new Set()) {
  for (const element of ast) {
    switch (element.type) {
      case TYPE.argument:
        into.add(`${element.value}:plain`);
        break;
      case TYPE.number:
        into.add(`${element.value}:number`);
        break;
      case TYPE.date:
      case TYPE.time:
        into.add(`${element.value}:date`);
        break;
      case TYPE.select:
      case TYPE.plural: {
        const kind = element.type === TYPE.select ? 'select' : 'plural';
        const options = Object.keys(element.options).sort();
        into.add(`${element.value}:${kind}[${options.join(',')}]`);
        for (const option of Object.values(element.options)) signatures(option.value, into);
        break;
      }
      case TYPE.tag:
        into.add(`<${element.value}>:tag`);
        signatures(element.children, into);
        break;
      default:
        break;
    }
  }
  return into;
}

function parseMessage(message) {
  try {
    return { ast: parse(message) };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}

function localeFiles(root) {
  const files = new Map(); // "<package>/<namespace>" -> { de: path, en: path }
  for (const pkg of readdirSync(root)) {
    const localeRoot = join(root, pkg, 'src', 'locales');
    if (!existsSync(localeRoot) || !statSync(localeRoot).isDirectory()) continue;
    for (const language of readdirSync(localeRoot)) {
      const dir = join(localeRoot, language);
      if (!statSync(dir).isDirectory()) continue;
      for (const file of readdirSync(dir).filter((name) => name.endsWith('.json'))) {
        const id = `${pkg}/${file.replace(/\.json$/, '')}`;
        const entry = files.get(id) ?? {};
        entry[language] = join(dir, file);
        files.set(id, entry);
      }
    }
  }
  return files;
}

/** Returns a list of human-readable problems; empty means the locales are consistent. */
export function checkLocales(root) {
  const problems = [];
  for (const [id, byLanguage] of localeFiles(root)) {
    const messages = {};
    for (const language of LANGUAGES) {
      const path = byLanguage[language];
      if (!path) {
        problems.push(`${id}: missing ${language} file`);
        continue;
      }
      let json;
      try {
        json = JSON.parse(readFileSync(path, 'utf8'));
      } catch (error) {
        problems.push(`${relative(root, path)}: invalid JSON (${error.message})`);
        continue;
      }
      messages[language] = flatten(json);
    }
    for (const language of Object.keys(byLanguage).filter((l) => !LANGUAGES.includes(l))) {
      problems.push(`${id}: unexpected language ${language}`);
    }
    if (!messages.de || !messages.en) continue;
    const keys = new Set([...Object.keys(messages.de), ...Object.keys(messages.en)]);
    for (const key of [...keys].sort()) {
      const de = messages.de[key];
      const en = messages.en[key];
      if (de === undefined || en === undefined) {
        problems.push(`${id}: key "${key}" exists only in ${de === undefined ? 'en' : 'de'}`);
        continue;
      }
      if (typeof de !== 'string' || typeof en !== 'string' || de.trim() === '' || en.trim() === '') {
        problems.push(`${id}: key "${key}" must be a non-empty string in both languages`);
        continue;
      }
      const parsed = { de: parseMessage(de), en: parseMessage(en) };
      let valid = true;
      for (const language of LANGUAGES) {
        if (parsed[language].error) {
          problems.push(`${id}: key "${key}" (${language}) is not valid ICU: ${parsed[language].error}`);
          valid = false;
        }
      }
      if (!valid) continue;
      const deSig = [...signatures(parsed.de.ast)].sort().join(' ');
      const enSig = [...signatures(parsed.en.ast)].sort().join(' ');
      if (deSig !== enSig) problems.push(`${id}: key "${key}" has different arguments: de {${deSig}} vs en {${enSig}}`);
    }
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve(process.argv[2] ?? new URL('..', import.meta.url).pathname);
  const problems = checkLocales(root);
  if (problems.length > 0) {
    console.error(problems.map((p) => `- ${p}`).join('\n'));
    process.exit(1);
  }
  console.log(`i18n check passed (${localeFiles(root).size} namespaces)`);
}
