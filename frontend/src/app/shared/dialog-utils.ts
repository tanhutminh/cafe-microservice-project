interface ClosableDialogRef<T> {
  close(result?: T): void;
}

function shallowEqual<T extends Record<string, unknown>>(a: T, b: T): boolean {
  return (Object.keys(a) as (keyof T)[]).every((key) => a[key] === b[key]);
}

/**
 * Closes an edit dialog with `current` only if it differs from `original` (shallow,
 * primitive-field comparison) - otherwise closes with no result, so a caller that gates its
 * update API call on a truthy afterClosed() result skips it entirely. `original: null` means
 * there's no baseline to compare against (creating new), so it always closes with `current`.
 */
export function closeIfChanged<T extends Record<string, unknown>>(
  dialogRef: ClosableDialogRef<T>,
  original: T | null,
  current: T
): void {
  const unchanged = original !== null && shallowEqual(original, current);
  dialogRef.close(unchanged ? undefined : current);
}
