import { createRoot } from 'react-dom/client';
import i18next from 'i18next';
import de from './locales/de/reference.json';
import { NotesWindow } from './NotesWindow';

void i18next.init({ lng: 'de', resources: { de: { translation: de } }, initAsync: false });
createRoot(document.getElementById('root')!).render(<NotesWindow t={i18next.t} notes={[]} />);
