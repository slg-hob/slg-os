import { render, screen } from '@testing-library/react';
import i18next from 'i18next';
import de from './locales/de/reference.json';
import en from './locales/en/reference.json';
import { NotesWindow } from './NotesWindow';

function translator(language: 'de' | 'en') {
  const instance = i18next.createInstance();
  void instance.init({ lng: language, resources: { de: { translation: de }, en: { translation: en } }, initAsync: false });
  return instance.t;
}

describe('NotesWindow', () => {
  it('shows the empty state in the active language', () => {
    render(<NotesWindow t={translator('en')} notes={[]} />);
    expect(screen.getByRole('heading', { name: 'Notes' })).toBeInTheDocument();
    expect(screen.getByText('No notes yet')).toBeInTheDocument();
  });

  it('lists the notes', () => {
    render(<NotesWindow t={translator('de')} notes={['Wareneingang prüfen']} />);
    expect(screen.getByRole('list')).toHaveTextContent('Wareneingang prüfen');
    expect(screen.queryByText('Keine Notizen vorhanden')).not.toBeInTheDocument();
  });
});
