import { createRoot } from 'react-dom/client';
import { I18nextProvider } from 'react-i18next';
import { App } from './App';
import { createI18n, resolveLanguage } from './i18n';

const language = resolveLanguage(null, navigator.languages);
document.documentElement.lang = language;
const i18n = createI18n(language);
createRoot(document.getElementById('root')!).render(
  <I18nextProvider i18n={i18n}>
    <App userName="" />
  </I18nextProvider>,
);
