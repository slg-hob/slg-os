import i18next from 'i18next';
import ICU from 'i18next-icu';
import { initReactI18next } from 'react-i18next';
import de from './locales/de/shell.json';
import en from './locales/en/shell.json';

/** Resolution order of PRD-0001 UI-24: user preference, browser language, then German. */
export function resolveLanguage(preference: string | null | undefined, browserLanguages: readonly string[]): 'de' | 'en' {
  const candidates = [preference, ...browserLanguages];
  for (const candidate of candidates) {
    const base = candidate?.toLowerCase().split('-')[0];
    if (base === 'de' || base === 'en') return base;
  }
  return 'de';
}

export function createI18n(language: 'de' | 'en') {
  const instance = i18next.createInstance();
  void instance.use(ICU).use(initReactI18next).init({
    lng: language,
    fallbackLng: 'en',
    defaultNS: 'shell',
    ns: ['shell'],
    resources: { de: { shell: de }, en: { shell: en } },
    initAsync: false,
  });
  return instance;
}
