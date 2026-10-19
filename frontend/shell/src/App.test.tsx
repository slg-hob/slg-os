import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { App } from './App';
import { createI18n, resolveLanguage } from './i18n';

describe('App', () => {
  it.each([
    ['de', 'Willkommen, Anna'],
    ['en', 'Welcome, Anna'],
  ] as const)('greets the user in %s', (language, expected) => {
    render(
      <I18nextProvider i18n={createI18n(language)}>
        <App userName="Anna" />
      </I18nextProvider>,
    );
    expect(screen.getByRole('heading', { name: 'SLG OS' })).toBeInTheDocument();
    expect(screen.getByText(expected)).toBeInTheDocument();
  });
});

describe('resolveLanguage', () => {
  it('prefers the user preference', () => {
    expect(resolveLanguage('en', ['de-DE'])).toBe('en');
  });

  it('falls back to the browser language', () => {
    expect(resolveLanguage(null, ['fr-FR', 'en-GB'])).toBe('en');
  });

  it('falls back to German', () => {
    expect(resolveLanguage(undefined, ['fr-FR'])).toBe('de');
    expect(resolveLanguage('xx', [])).toBe('de');
  });
});
