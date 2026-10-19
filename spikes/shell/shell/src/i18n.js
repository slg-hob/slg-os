// Shell strings (de/en). Module strings are loaded lazily from the module's static assets.
export const shellMessages = {
  en: { launcher: 'Launcher', open: 'Open', close: 'Close', theme: 'Theme', language: 'Language', light: 'Light', dark: 'Dark', loading: 'Loading…', retry: 'Retry', failed: 'This module failed to load', noModules: 'No modules available', mismatch: 'Incompatible module version' },
  de: { launcher: 'Startmenü', open: 'Öffnen', close: 'Schließen', theme: 'Design', language: 'Sprache', light: 'Hell', dark: 'Dunkel', loading: 'Lädt…', retry: 'Erneut versuchen', failed: 'Dieses Modul konnte nicht geladen werden', noModules: 'Keine Module verfügbar', mismatch: 'Inkompatible Modulversion' },
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
