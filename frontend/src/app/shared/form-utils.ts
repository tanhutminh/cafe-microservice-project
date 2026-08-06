/**
 * Returns a copy of `value` with the given string fields trimmed. Skips non-string fields
 * silently (useful when spreading a form's whole raw value and only some fields are text).
 */
export function trimFields<T extends object>(value: T, keys: (keyof T)[]): T {
  const result: T = { ...value };
  for (const key of keys) {
    const field = result[key];
    if (typeof field === 'string') {
      result[key] = field.trim() as T[typeof key];
    }
  }
  return result;
}
