import { isValidModuleId } from './moduleId';

describe('isValidModuleId', () => {
  it.each(['reference', 'lead-radar', 'abc', 'a' + 'b'.repeat(31)])('accepts %s', (id) => {
    expect(isValidModuleId(id)).toBe(true);
  });

  it.each(['ab', 'Ab1', '1abc', '-abc', 'a_bc', 'a b c', 'a' + 'b'.repeat(32), '', 'äbc'])('rejects %j', (id) => {
    expect(isValidModuleId(id)).toBe(false);
  });

  it.each([null, undefined, 42, {}])('rejects the non-string %s', (id) => {
    expect(isValidModuleId(id)).toBe(false);
  });
});
