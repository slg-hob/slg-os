import { useShell } from 'spike-sdk';

export default function App() {
  const { t } = useShell();
  return (
    <div className="p-3" data-testid="mod-mismatch">
      <h2 className="h5">{t('mismatch', 'title')}</h2>
      <p>{t('mismatch', 'hello')}</p>
    </div>
  );
}
