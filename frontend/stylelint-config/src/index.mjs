// Stylelint configuration (PRD-0005 VL-5 AC3). Two variants:
//  - moduleConfig: module code. Tokens only: no raw colours, no !important, no global selectors, no --bs-* use or override,
//    no network or data: URLs, no shell or glass classes.
//  - platformConfig: shell and design-system code. Same, except that the platform may declare --bs-* variables and
//    the generated token files are exempt (ignoreFiles).

const COLOR_FUNCTIONS = ['rgb', 'rgba', 'hsl', 'hsla', 'hwb', 'lab', 'lch', 'oklab', 'oklch', 'color', 'color-mix', 'device-cmyk'];

const sharedRules = {
  'color-no-hex': [true, { message: 'Raw colours are not allowed. Use var(--slg-*).' }],
  'color-named': ['never', { message: 'Named colours are not allowed. Use var(--slg-*).' }],
  'function-disallowed-list': [COLOR_FUNCTIONS, { message: 'Colour functions are not allowed. Use var(--slg-*).' }],
  'declaration-no-important': [true, { message: '!important is not allowed.' }],
  'function-url-scheme-disallowed-list': [['data', 'http', 'https'], { message: 'No data: URLs and no network requests (CSP, no external runtime dependencies).' }],
  'at-rule-disallowed-list': [['import'], { message: '@import is not allowed; compose styles in the build.' }],
  'declaration-property-value-disallowed-list': [{ '/.*/': ['/var\\(\\s*--bs-/'] }, { message: 'Use --slg-* tokens, not --bs-* variables.' }],
};

const globalSelectorRules = {
  'selector-max-universal': [0, { message: 'Global selectors are not allowed.' }],
  'selector-max-type': [0, { ignore: ['descendant', 'child', 'compounded'], message: 'Bare element selectors are global; scope them with a class.' }],
  'selector-disallowed-list': [['/(^|[\\s,>+~])(html|body)(?![\\w-])/', '/:root/', '/:host/'], { message: 'Global selectors are not allowed.' }],
};

/** Strict rules for module code. */
export const moduleConfig = {
  rules: {
    ...sharedRules,
    ...globalSelectorRules,
    'custom-property-pattern': ['^(?!bs-).+', { message: 'Modules must not declare --bs-* variables.' }],
    'selector-disallowed-list': [
      ['/(^|[\\s,>+~])(html|body)(?![\\w-])/', '/:root/', '/:host/', '/\\.slg-shell-/', '/\\.slg-glass(?![\\w-])/'],
      { message: 'Global selectors, shell classes and glass classes are not allowed in module code.' },
    ],
  },
};

/** Rules for shell and design-system code: may declare --bs-* variables and style the document (base layer). */
export const platformConfig = { rules: { ...sharedRules } };

/** Generated files are verified by the tokens-sync check instead. */
export const ignoreFiles = ['**/generated/**', '**/dist/**', '**/node_modules/**', '**/coverage/**'];
