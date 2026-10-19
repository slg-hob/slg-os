import type { TFunction } from 'i18next';

interface NotesWindowProps {
  t: TFunction;
  notes: readonly string[];
}

/** Placeholder window of the reference module (PRD-0003 SK-13 follows). Texts come from the module's i18n bundle. */
export function NotesWindow({ t, notes }: NotesWindowProps) {
  return (
    <section aria-labelledby="notes-title">
      <h2 id="notes-title">{t('notes.title')}</h2>
      {notes.length === 0 ? (
        <p>{t('notes.empty')}</p>
      ) : (
        <ul>
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
