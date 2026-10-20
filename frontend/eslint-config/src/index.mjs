// ESLint rules for module code (PRD-0005 VL-5 AC3, VL-10 AC7): modules inherit the look through tokens and components only.
// Rejected in module code: raw colours, inline styles, --bs-* variables, password inputs, shell dialog classes, glass classes,
// raw HTML injection. Inline "eslint-disable" comments are ignored for these rules (noInlineConfig).
// "!important" and global selectors are CSS rules and live in @slg-os/stylelint-config.

const COLOR_FUNCTIONS = 'rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color|color-mix';
const HEX = '(?:^|[\\s:(,])#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})(?![0-9a-zA-Z_-])';
const PASSWORD = '^password$';

/** Both string literals and template parts, because a template can hold the same text. */
const inText = (pattern, message) => [
  { selector: `Literal[value=/${pattern}/]`, message },
  { selector: `TemplateElement[value.raw=/${pattern}/]`, message },
];

const NO_PASSWORD = 'Modules must not render password inputs. Sign-in and step-up happen on the Keycloak origin only.';
const NO_RAW_HTML = 'Raw HTML can create inputs and style elements that imitate the shell. Render elements instead.';

/** Syntax selectors that module code must not contain. */
export const moduleRestrictedSyntax = Object.freeze([
  { selector: 'JSXAttribute[name.name="style"]', message: 'Inline styles are not allowed (CSP style-src self). Use a class from the design system or a custom property on a class.' },
  { selector: 'CallExpression[callee.property.name="setAttribute"][arguments.0.value=/^style$/i]', message: 'Inline styles are not allowed. Set a --slg-* custom property through the CSSOM instead.' },
  { selector: 'AssignmentExpression[left.property.name="cssText"]', message: 'cssText writes an inline style. Set a custom property through the CSSOM instead.' },
  { selector: 'CallExpression[callee.property.name="createElement"][arguments.0.value=/^style$/i]', message: 'Style elements injected at run time are not allowed (CSP style-src self).' },
  { selector: 'JSXAttribute[name.name="dangerouslySetInnerHTML"]', message: NO_RAW_HTML },
  { selector: 'AssignmentExpression[left.property.name=/^(innerHTML|outerHTML)$/]', message: NO_RAW_HTML },
  { selector: 'CallExpression[callee.property.name="insertAdjacentHTML"]', message: NO_RAW_HTML },
  ...inText(`(?:${COLOR_FUNCTIONS})\\(`, 'Raw colours are not allowed in module code. Use a --slg-* token.'),
  ...inText(HEX, 'Raw colours are not allowed in module code. Use a --slg-* token.'),
  ...inText('--bs-', 'Modules must not use or override Bootstrap variables (--bs-*). Use --slg-* tokens.'),
  { selector: `JSXAttribute[name.name="type"] Literal[value=/${PASSWORD}/i]`, message: NO_PASSWORD },
  { selector: `JSXAttribute[name.name="type"] TemplateElement[value.raw=/${PASSWORD}/i]`, message: NO_PASSWORD },
  { selector: `CallExpression[callee.property.name="setAttribute"][arguments.0.value="type"][arguments.1.value=/${PASSWORD}/i]`, message: NO_PASSWORD },
  { selector: `AssignmentExpression[left.property.name="type"][right.value=/${PASSWORD}/i]`, message: NO_PASSWORD },
  { selector: `Property[key.name="type"][value.value=/${PASSWORD}/i]`, message: NO_PASSWORD },
  ...inText('(?<![\\w-])slg-shell-', 'The slg-shell-* classes are reserved for shell dialogs and notices that modules must not imitate.'),
  ...inText('(?<![\\w-])slg-glass(?![\\w-])', 'Glass is chrome only. Module content sits on solid surfaces (VL-8 AC2).'),
]);

/**
 * Flat configuration object for module code.
 * @param {{files: string[], ignores?: string[], extraRestrictedSyntax?: object[]}} options
 *   extraRestrictedSyntax: selectors of other configs for the same files; "no-restricted-syntax" is one rule, so they must be merged.
 */
export function moduleDesignRules({ files, ignores = ['**/*.test.{ts,tsx,mjs}'], extraRestrictedSyntax = [] }) {
  return {
    files,
    ignores,
    linterOptions: { noInlineConfig: true },
    rules: { 'no-restricted-syntax': ['error', ...extraRestrictedSyntax, ...moduleRestrictedSyntax] },
  };
}
