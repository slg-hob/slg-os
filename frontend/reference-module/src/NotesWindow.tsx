import type { TFunction } from 'i18next';
import { useId } from 'react';

interface NotesWindowProps {
  t: TFunction;
  notes: readonly string[];
}

/** Placeholder window of the reference module (PRD-0003 SK-13 follows). Texts come from the module's i18n bundle. */
export function NotesWindow({ t, notes }: NotesWindowProps) {
  const titleId = useId();
  return (
    <section aria-labelledby={titleId}>
      <h2 id={titleId}>{t('notes.title')}</h2>
      {notes.length === 0 ? (
        <p>{t('notes.empty')}</p>
      ) : (
        <ul>
          {notes.map((note, index) => (
            <li key={`${index}:${note}`}>{note}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
