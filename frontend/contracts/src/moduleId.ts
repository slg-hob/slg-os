/** Module ids are unique and immutable: lower case letter first, then letters, digits or dashes, 3 to 32 characters (PRD-0001 FR-4). */
const MODULE_ID_PATTERN = /^[a-z][a-z0-9-]{2,31}$/;

export function isValidModuleId(id: unknown): id is string {
  return typeof id === 'string' && MODULE_ID_PATTERN.test(id);
}
