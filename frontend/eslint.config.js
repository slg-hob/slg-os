import js from '@eslint/js';
import i18next from 'eslint-plugin-i18next';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

/**
 * Lint rules of the frontend workspace (PRD-0003 SK-2 AC1): no literal user-visible strings in markup,
 * including the attributes that screen readers announce.
 */
export const noLiteralStringOptions = {
  mode: 'jsx-only',
  'jsx-attributes': {
    include: ['title', 'aria-label', 'alt', 'placeholder', 'aria-description', 'aria-valuetext', 'aria-roledescription'],
    exclude: ['className', 'style', 'type', 'key', 'id', 'width', 'height', 'role', 'lang', 'dir', 'name', 'htmlFor',
      'data-testid', 'aria-labelledby', 'aria-describedby', 'aria-live', 'aria-current', 'aria-controls', 'href', 'src', 'rel', 'target'],
  },
};

const ANNOUNCED_ATTRIBUTES = ['title', 'aria-label', 'alt', 'placeholder', 'aria-description', 'aria-valuetext', 'aria-roledescription'];
const attributes = ANNOUNCED_ATTRIBUTES.join('|');

/**
 * The plugin option "jsx-attributes.include" has no effect in jsx-only mode, so the attributes screen readers
 * announce, template literals in markup and document.title are covered by syntax selectors.
 */
export const literalTextSelectors = [
  {
    selector: `JSXAttribute[name.name=/^(${attributes})$/] > Literal`,
    message: 'User-visible attribute text must come from the i18n bundle.',
  },
  {
    selector: `JSXAttribute[name.name=/^(${attributes})$/] > JSXExpressionContainer > :matches(Literal[value=/\\S/], TemplateLiteral)`,
    message: 'User-visible attribute text must come from the i18n bundle.',
  },
  {
    selector: 'JSXElement > JSXExpressionContainer > TemplateLiteral',
    message: 'User-visible text must come from the i18n bundle.',
  },
  {
    selector: 'AssignmentExpression[left.object.name="document"][left.property.name="title"][right.type=/Literal|TemplateLiteral/]',
    message: 'The document title must come from the i18n bundle.',
  },
];

export default tseslint.config(
  { ignores: ['**/dist/**', '**/coverage/**', '**/node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: { ...reactHooks.configs.recommended.rules },
  },
  {
    files: ['*/src/**/*.tsx'],
    ignores: ['**/*.test.tsx'],
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': ['error', noLiteralStringOptions],
      'no-restricted-syntax': ['error', ...literalTextSelectors],
    },
  },
  {
    files: ['tools/**/*.mjs', '*.js'],
    languageOptions: { globals: { process: 'readonly', console: 'readonly', URL: 'readonly' } },
  },
);
