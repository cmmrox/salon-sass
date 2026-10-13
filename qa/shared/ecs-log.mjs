// ECS fields can be serialized as nested objects; expose their documented dotted names.
// Preserve every original field so secret-marker assertions still inspect the whole record.
export function ecsRecord(record) {
  const result = { ...record };
  function visit(value, prefix) {
    for (const [key, item] of Object.entries(value)) {
      const name = prefix ? `${prefix}.${key}` : key;
      if (item && typeof item === 'object' && !Array.isArray(item)) visit(item, name);
      else result[name] = item;
    }
  }
  visit(record, '');
  return result;
}
