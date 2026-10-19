// Shell strings (de/en). Module strings are loaded lazily from the module's static assets.
export const shellMessages = {
  en: { launcher: 'Launcher', open: 'Open', close: 'Close', theme: 'Theme', language: 'Language', light: 'Light', dark: 'Dark', loading: 'Loading…', retry: 'Retry', failed: 'This module failed to load', noModules: 'No modules available', mismatch: 'Incompatible module version',
    minimize: 'Minimize', maximize: 'Maximize', restore: 'Restore', closeWindow: 'Close', windowMenu: 'Window menu',
    move: 'Move', resize: 'Resize', snapLeft: 'Snap left', snapRight: 'Snap right', openWindows: 'Open windows', taskbar: 'Taskbar',
    skipTaskbar: 'Skip to taskbar', skipWindow: 'Skip to active window', emptyDesk: 'No windows open. Use the launcher to open a module.',
    confirmCloseTitle: 'Close window?', confirmCloseText: 'Unsaved changes will be lost.', stay: 'Stay', closeAnyway: 'Close anyway',
    minimized: 'minimized', notAvailable: 'This content is not available.', restoreSkipped: 'Some windows could not be restored.',
    kbdMove: 'Move mode: use the arrow keys, Enter to confirm, Escape to cancel', kbdResize: 'Resize mode: use the arrow keys, Enter to confirm, Escape to cancel',
    announceOpened: '{name} opened, window {n} of {total}', announceClosed: '{name} closed', announceMinimized: '{name} minimized',
    announceActive: '{name} active', announceMaximized: '{name} maximized', announceRestored: '{name} restored', announceSnapped: '{name} snapped',
    announceRestoredN: '{n} windows restored', signOut: 'Sign out', sessionExpired: 'Session expired.', signInAgain: 'Sign in again' },
  de: { launcher: 'Startmenü', open: 'Öffnen', close: 'Schließen', theme: 'Design', language: 'Sprache', light: 'Hell', dark: 'Dunkel', loading: 'Lädt…', retry: 'Erneut versuchen', failed: 'Dieses Modul konnte nicht geladen werden', noModules: 'Keine Module verfügbar', mismatch: 'Inkompatible Modulversion',
    minimize: 'Minimieren', maximize: 'Maximieren', restore: 'Wiederherstellen', closeWindow: 'Schließen', windowMenu: 'Fenstermenü',
    move: 'Verschieben', resize: 'Größe ändern', snapLeft: 'Links andocken', snapRight: 'Rechts andocken', openWindows: 'Geöffnete Fenster', taskbar: 'Taskleiste',
    skipTaskbar: 'Zur Taskleiste springen', skipWindow: 'Zum aktiven Fenster springen', emptyDesk: 'Keine Fenster geöffnet. Öffnen Sie ein Modul über das Startmenü.',
    confirmCloseTitle: 'Fenster schließen?', confirmCloseText: 'Nicht gespeicherte Änderungen gehen verloren.', stay: 'Bleiben', closeAnyway: 'Trotzdem schließen',
    minimized: 'minimiert', notAvailable: 'Dieser Inhalt ist nicht verfügbar.', restoreSkipped: 'Einige Fenster konnten nicht wiederhergestellt werden.',
    kbdMove: 'Verschiebemodus: Pfeiltasten benutzen, Eingabe bestätigt, Escape bricht ab', kbdResize: 'Größenmodus: Pfeiltasten benutzen, Eingabe bestätigt, Escape bricht ab',
    announceOpened: '{name} geöffnet, Fenster {n} von {total}', announceClosed: '{name} geschlossen', announceMinimized: '{name} minimiert',
    announceActive: '{name} aktiv', announceMaximized: '{name} maximiert', announceRestored: '{name} wiederhergestellt', announceSnapped: '{name} angedockt',
    announceRestoredN: '{n} Fenster wiederhergestellt', signOut: 'Abmelden', sessionExpired: 'Sitzung abgelaufen.', signInAgain: 'Erneut anmelden' },
};

const moduleBundles = {};

export async function loadModuleBundle(moduleId, lang) {
  const key = `${moduleId}:${lang}`;
  if (moduleBundles[key]) return moduleBundles[key];
  const res = await fetch(`/modules/${moduleId}/remote/i18n/${lang}/${moduleId}.json`);
  if (!res.ok) throw new Error(`i18n bundle ${key} failed: ${res.status}`);
  moduleBundles[key] = await res.json();
  return moduleBundles[key];
}

export function lookup(moduleId, lang, key) {
  return moduleBundles[`${moduleId}:${lang}`]?.[key] ?? moduleBundles[`${moduleId}:en`]?.[key] ?? key;
}
