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
    rules: { 'i18next/no-literal-string': ['error', noLiteralStringOptions] },
  },
  {
    files: ['tools/**/*.mjs', '*.js'],
    languageOptions: { globals: { process: 'readonly', console: 'readonly', URL: 'readonly' } },
  },
);
