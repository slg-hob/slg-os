import { useShell } from 'spike-sdk';

export default function App() {
  const { t } = useShell();
  return (
    <div className="p-3" data-testid="mod-b">
      <h2 className="h5">{t('b', 'title')}</h2>
      <p>{t('b', 'hello')}</p>
      <h3 className="h6">{t('b', 'items')}</h3>
      <ul className="list-group"><li className="list-group-item">1</li><li className="list-group-item">2</li></ul>
    </div>
  );
}
