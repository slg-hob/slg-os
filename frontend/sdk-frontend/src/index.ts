/** Module contract version implemented by this SDK (PRD-0001 FR-6, Q13: only the current major is supported). */
export const CONTRACT_VERSION = '1.0';

const VERSION_PATTERN = /^(\d{1,4})\.(\d{1,5})$/;

/** Tells whether a declared contract version belongs to the supported major and is not newer than this SDK. */
export function isSupportedContract(declared: string | null | undefined): boolean {
  if (typeof declared !== 'string') return false;
  const match = VERSION_PATTERN.exec(declared);
  if (!match) return false;
  const [currentMajor, currentMinor] = CONTRACT_VERSION.split('.').map(Number);
  return Number(match[1]) === currentMajor && Number(match[2]) <= (currentMinor ?? 0);
}
