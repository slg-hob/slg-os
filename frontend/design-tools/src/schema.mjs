// A small JSON Schema validator for the token sources: no dependency, no network access (PRD-0005 VL-5 AC1).
// Supported keywords: $ref (local "#/$defs/x"), type, enum, pattern, minLength, maxLength, minimum, maximum,
// required, properties, additionalProperties (false only), items, minItems, oneOf.
// Any other keyword in a schema is rejected, so a schema cannot silently contain a rule that is not enforced.

const KNOWN = new Set(['$ref', '$comment', 'type', 'enum', 'pattern', 'minLength', 'maxLength', 'minimum', 'maximum',
  'required', 'properties', 'additionalProperties', 'items', 'minItems', 'oneOf']);

const typeOf = (value) => {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (Number.isInteger(value)) return 'integer';
  return typeof value;
};

const typeMatches = (expected, value) => {
  const actual = typeOf(value);
  return actual === expected || (expected === 'number' && actual === 'integer');
};

/**
 * Validates data against the schema node `node` of `root` and returns a list of error strings ("path: message").
 * @param {object} root the schema document (holds $defs)
 * @param {object} node the schema node to apply
 * @param {unknown} data
 * @param {string} [path]
 * @returns {string[]}
 */
export function validate(root, node, data, path = '$') {
  for (const key of Object.keys(node)) {
    if (!KNOWN.has(key)) return [`${path}: schema keyword "${key}" is not supported`];
  }
  if (node.$ref !== undefined) {
    const match = /^#\/\$defs\/([A-Za-z0-9_-]+)$/.exec(node.$ref);
    const target = match ? root.$defs?.[match[1]] : undefined;
    if (!target) return [`${path}: unresolved reference ${node.$ref}`];
    return validate(root, target, data, path);
  }
  const errors = [];
  if (node.type !== undefined && !typeMatches(node.type, data)) {
    return [`${path}: expected ${node.type}, got ${typeOf(data)}`];
  }
  if (node.enum !== undefined && !node.enum.includes(data)) errors.push(`${path}: must be one of ${node.enum.join(', ')}`);
  if (typeof data === 'string') {
    if (node.minLength !== undefined && data.length < node.minLength) errors.push(`${path}: shorter than ${node.minLength}`);
    if (node.maxLength !== undefined && data.length > node.maxLength) errors.push(`${path}: longer than ${node.maxLength}`);
    if (node.pattern !== undefined && !new RegExp(node.pattern).test(data)) errors.push(`${path}: does not match ${node.pattern}`);
  }
  if (typeof data === 'number') {
    if (node.minimum !== undefined && data < node.minimum) errors.push(`${path}: below ${node.minimum}`);
    if (node.maximum !== undefined && data > node.maximum) errors.push(`${path}: above ${node.maximum}`);
  }
  if (Array.isArray(data)) {
    if (node.minItems !== undefined && data.length < node.minItems) errors.push(`${path}: needs at least ${node.minItems} items`);
    if (node.items !== undefined) data.forEach((item, i) => errors.push(...validate(root, node.items, item, `${path}[${i}]`)));
  }
  if (typeOf(data) === 'object') {
    for (const key of node.required ?? []) {
      if (!Object.hasOwn(data, key)) errors.push(`${path}: missing "${key}"`);
    }
    const properties = node.properties ?? {};
    for (const [key, value] of Object.entries(data)) {
      if (Object.hasOwn(properties, key)) errors.push(...validate(root, properties[key], value, `${path}.${key}`));
      else if (node.additionalProperties === false) errors.push(`${path}: unexpected property "${key}"`);
    }
  }
  if (node.oneOf !== undefined) {
    const matching = node.oneOf.filter((option) => validate(root, option, data, path).length === 0).length;
    if (matching !== 1) errors.push(`${path}: must match exactly one of ${node.oneOf.length} shapes, matches ${matching}`);
  }
  return errors;
}
