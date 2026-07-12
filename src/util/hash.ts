/**
 * FNV-1a 32-bit hash, returned as base36. Dependency-free and stable across
 * runs, which is exactly what row fingerprints need: they let write-back
 * re-locate a source table row even if its line number shifted.
 */
export function fnv1a(input: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(36);
}
