import { validate } from './schema.mjs';

const root = {
  $defs: {
    name: { type: 'string', pattern: '^[a-z]+$', minLength: 2, maxLength: 5 },
    item: { type: 'object', required: ['name'], additionalProperties: false, properties: { name: { $ref: '#/$defs/name' }, n: { type: 'integer', minimum: 1, maximum: 9 } } },
    list: { type: 'array', minItems: 1, items: { $ref: '#/$defs/item' } },
    either: { oneOf: [{ type: 'string' }, { type: 'integer' }] },
  },
};
const check = (def, data) => validate(root, { $ref: `#/$defs/${def}` }, data, 'x');

describe('validate', () => {
  it('accepts valid data', () => {
    expect(check('list', [{ name: 'abc', n: 3 }, { name: 'de' }])).toEqual([]);
  });

  it.each([
    ['a wrong type', 'list', 'text', 'x: expected array, got string'],
    ['an empty array', 'list', [], 'x: needs at least 1 items'],
    ['a missing property', 'list', [{}], 'x[0]: missing "name"'],
    ['an unexpected property', 'list', [{ name: 'abc', extra: 1 }], 'x[0]: unexpected property "extra"'],
    ['a pattern mismatch', 'list', [{ name: 'AB' }], 'x[0].name: does not match ^[a-z]+$'],
    ['a string that is too short', 'list', [{ name: 'a' }], 'x[0].name: shorter than 2'],
    ['a string that is too long', 'list', [{ name: 'abcdef' }], 'x[0].name: longer than 5'],
    ['a number below the minimum', 'list', [{ name: 'abc', n: 0 }], 'x[0].n: below 1'],
    ['a number above the maximum', 'list', [{ name: 'abc', n: 10 }], 'x[0].n: above 9'],
    ['a float where an integer is required', 'list', [{ name: 'abc', n: 1.5 }], 'x[0].n: expected integer, got number'],
    ['null where a string is required', 'list', [{ name: null }], 'x[0].name: expected string, got null'],
  ])('reports %s', (_label, def, data, message) => {
    expect(check(def, data)).toContain(message);
  });

  it('checks enums', () => {
    expect(validate(root, { enum: ['a', 'b'] }, 'c', 'x')).toEqual(['x: must be one of a, b']);
    expect(validate(root, { enum: ['a', 'b'] }, 'a', 'x')).toEqual([]);
  });

  it('accepts an integer for type number', () => {
    expect(validate(root, { type: 'number' }, 3, 'x')).toEqual([]);
  });

  it('requires exactly one oneOf option to match', () => {
    expect(check('either', 'text')).toEqual([]);
    expect(check('either', 3)).toEqual([]);
    expect(check('either', true)).toEqual(['x: must match exactly one of 2 shapes, matches 0']);
    const overlapping = { oneOf: [{ type: 'integer' }, { type: 'number' }] };
    expect(validate(root, overlapping, 3, 'x')).toEqual(['x: must match exactly one of 2 shapes, matches 2']);
  });

  it('rejects unresolved references and unsupported keywords', () => {
    expect(validate(root, { $ref: '#/$defs/missing' }, 1, 'x')).toEqual(['x: unresolved reference #/$defs/missing']);
    expect(validate(root, { $ref: 'http://example.org/schema' }, 1, 'x')).toEqual(['x: unresolved reference http://example.org/schema']);
    expect(validate(root, { format: 'email' }, 'a', 'x')).toEqual(['x: schema keyword "format" is not supported']);
  });

  it('does not treat inherited properties as present', () => {
    const data = JSON.parse('{"__proto__": 1, "name": "abc"}');
    expect(check('item', data)).toContain('x: unexpected property "__proto__"');
    expect(check('item', {})).toContain('x: missing "name"');
  });
});
