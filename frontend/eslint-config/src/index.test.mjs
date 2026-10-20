import { Linter } from 'eslint';
import tseslint from 'typescript-eslint';
import { moduleDesignRules, moduleRestrictedSyntax } from './index.mjs';

const linter = new Linter({ configType: 'flat' });
const config = [
  {
    files: ['**/*.tsx', '**/*.ts'],
    languageOptions: { parser: tseslint.parser, parserOptions: { ecmaFeatures: { jsx: true } } },
  },
  moduleDesignRules({ files: ['**/*.tsx', '**/*.ts'] }),
];

/** Lints a module source and returns the messages of the restricted-syntax rule. */
const lint = (code, filename = 'module.tsx') => linter.verify(code, config, filename).map((m) => m.message);

describe('module design rules', () => {
  it('accepts a module that uses classes, tokens and the CSSOM', () => {
    const code = `
      export function Panel() {
        document.documentElement.style.setProperty('--slg-space-2', '8px');
        return <section className="card" aria-labelledby="t"><a href="/x">x</a><input type="text" /></section>;
      }`;
    expect(lint(code)).toEqual([]);
  });

  it.each([
    ['inline style attribute', 'const a = <div style={{ width: 1 }} />;', /Inline styles/],
    ['setAttribute style', "el.setAttribute('style', 'a:b');", /Inline styles/],
    ['cssText', "el.style.cssText = 'a:b';", /cssText/],
    ['injected style element', "document.createElement('style');", /Style elements/],
    ['hex colour', "const c = '#00aeef';", /Raw colours/],
    ['short hex colour', "const c = '#fff';", /Raw colours/],
    ['hex colour inside a declaration', "const c = 'border: 1px solid #ABCDEF';", /Raw colours/],
    ['rgb function', "const c = 'rgb(0, 0, 0)';", /Raw colours/],
    ['rgba function', "const c = `rgba(0,0,0,${a})`;", /Raw colours/],
    ['oklch function', "const c = 'oklch(0.5 0.1 200)';", /Raw colours/],
    ['bootstrap variable', "const v = 'var(--bs-body-bg)';", /--bs-/],
    ['bootstrap variable in a template', 'const v = `--bs-${name}`;', /--bs-/],
    ['password input in JSX', 'const a = <input type="password" />;', /password/],
    ['password input through setAttribute', "i.setAttribute('type', 'password');", /password/],
    ['password input through assignment', "i.type = 'password';", /password/],
    ['password input through createElement props', "h('input', { type: 'password' });", /password/],
    ['password input in an expression container', "const a = <input type={'password'} />;", /password/],
    ['password input in a template', 'const a = <input type={`password`} />;', /password/],
    ['password input in upper case', 'const a = <input type="PASSWORD" />;', /password/],
    ['password input chosen by a condition', "const a = <input type={x ? 'password' : 'text'} />;", /password/],
    ['style element in upper case', "document.createElement('STYLE');", /Style elements/],
    ['style attribute in upper case', "el.setAttribute('STYLE', 'a:b');", /Inline styles/],
    ['dangerouslySetInnerHTML', 'const a = <div dangerouslySetInnerHTML={{ __html: x }} />;', /Raw HTML/],
    ['innerHTML', "el.innerHTML = '<b>x</b>';", /Raw HTML/],
    ['outerHTML', "el.outerHTML = '<b>x</b>';", /Raw HTML/],
    ['insertAdjacentHTML', "el.insertAdjacentHTML('beforeend', x);", /Raw HTML/],
    ['shell dialog class', 'const a = <div className="slg-shell-dialog" />;', /reserved/],
    ['shell class among others', 'const a = <div className={`box slg-shell-notice`} />;', /reserved/],
    ['glass class', 'const a = <div className="slg-glass pane" />;', /Glass is chrome/],
  ])('rejects %s', (_label, code, message) => {
    const messages = lint(code);
    expect(messages.length).toBeGreaterThan(0);
    expect(messages.join('\n')).toMatch(message);
  });

  it.each([
    ['an anchor id longer than a colour', "const a = '#section-intro';"],
    ['a hex-like word inside text', "const a = 'issue#123456789 is open';"],
    ['the word color in prose', "const a = 'choose a color';"],
    ['a class that only resembles the shell prefix', "const a = 'my-slg-shell-like';"],
    ['a text input', 'const a = <input type="text" />;'],
    ['the glass word in another class', "const a = 'slg-glassy';"],
  ])('does not flag %s', (_label, code) => {
    expect(lint(code)).toEqual([]);
  });

  it('cannot be switched off with an inline directive', () => {
    const code = "// eslint-disable-next-line no-restricted-syntax\nconst c = '#00aeef';\n/* eslint-disable */\nconst d = 'rgb(0, 0, 0)';";
    expect(lint(code).filter((m) => /Raw colours/.test(m))).toHaveLength(2);
  });

  it('ignores test files by default and honours extra selectors', () => {
    const rules = moduleDesignRules({ files: ['**/*.tsx'], extraRestrictedSyntax: [{ selector: 'Identifier[name="forbidden"]', message: 'extra' }] });
    expect(rules.ignores).toContain('**/*.test.{ts,tsx,mjs}');
    expect(rules.rules['no-restricted-syntax'].slice(1, 2)).toEqual([{ selector: 'Identifier[name="forbidden"]', message: 'extra' }]);
    expect(rules.rules['no-restricted-syntax']).toHaveLength(1 + 1 + moduleRestrictedSyntax.length);
  });
});
