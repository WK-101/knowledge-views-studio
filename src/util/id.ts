let counter = 0;

/**
 * Generate a reasonably unique, human-skimmable id with a semantic prefix,
 * e.g. `profile-lm3k9f-a1b2c3`. Single source of truth for the whole plugin
 * (the legacy codebase defined this twice).
 */
export function createId(prefix = "id"): string {
  counter = (counter + 1) % 0xffffff;
  const time = Date.now().toString(36);
  const rand = Math.random().toString(36).slice(2, 6);
  const seq = counter.toString(36);
  return `${prefix}-${time}-${rand}${seq}`;
}
