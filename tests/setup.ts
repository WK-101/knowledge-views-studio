/**
 * Obsidian plugins must use `window.setTimeout` / `window.clearTimeout` so timers behave correctly in
 * popout windows -- its linter enforces this and does not allow an exception. The test suite runs under
 * Node, which has no `window`, so shim it here.
 *
 * This is the one place `globalThis` is genuinely correct: it is the only way to reach the global object
 * in an environment that, by definition, has no `window` yet.
 */
const g = globalThis as unknown as Record<string, unknown>;
if (typeof g["window"] === "undefined") g["window"] = globalThis;
