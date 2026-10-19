import { useState } from 'react';
import { useShell } from 'spike-sdk';

function Crash() {
  throw new Error('Deliberate render error in module A');
}

export default function App() {
  const { t, theme, language } = useShell();
  const [crash, setCrash] = useState(false);
  const [text, setText] = useState('');
  if (crash) return <Crash />;
  return (
    <div className="p-3" data-testid="mod-a">
      <h2 className="h5">{t('a', 'title')}</h2>
      <p>{t('a', 'hello')}</p>
      <p className="text-body-secondary">
        {t('a', 'theme')}: <span data-testid="a-theme">{theme}</span>, {t('a', 'lang')}: <span data-testid="a-lang">{language}</span>
      </p>
      <label className="form-label" htmlFor="a-input">{t('a', 'input')}</label>
      <input id="a-input" className="form-control mb-2" value={text} onChange={(e) => setText(e.target.value)} />
      <button className="btn btn-danger btn-sm" onClick={() => setCrash(true)}>{t('a', 'crash')}</button>
    </div>
  );
}
