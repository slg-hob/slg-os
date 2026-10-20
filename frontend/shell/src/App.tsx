import { useTranslation } from 'react-i18next';

interface AppProps {
  userName: string;
}

/** Placeholder shell root. The desktop, window manager and session handling follow in later milestones (PRD-0003 M4). */
export function App({ userName }: AppProps) {
  const { t } = useTranslation();
  return (
    <main>
      <h1>{t('app.title')}</h1>
      <p>{t('app.welcome', { name: userName })}</p>
    </main>
  );
}
