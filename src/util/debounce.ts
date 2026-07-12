/**
 * A trailing-edge debounce with explicit `cancel`, `flush`, and `isPending`.
 * Used for coalescing persistence and refresh work — the legacy plugin saved on
 * every keystroke and never coalesced refreshes.
 */
export interface Debounced<A extends unknown[]> {
  (...args: A): void;
  cancel(): void;
  flush(): void;
  isPending(): boolean;
}

export function debounce<A extends unknown[]>(
  fn: (...args: A) => void,
  waitMs: number,
): Debounced<A> {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let lastArgs: A | null = null;

  const invoke = (): void => {
    timer = null;
    const args = lastArgs;
    lastArgs = null;
    if (args) fn(...args);
  };

  const debounced = ((...args: A): void => {
    lastArgs = args;
    if (timer) clearTimeout(timer);
    timer = setTimeout(invoke, waitMs);
  }) as Debounced<A>;

  debounced.cancel = (): void => {
    if (timer) clearTimeout(timer);
    timer = null;
    lastArgs = null;
  };

  debounced.flush = (): void => {
    if (timer) {
      clearTimeout(timer);
      invoke();
    }
  };

  debounced.isPending = (): boolean => timer !== null;

  return debounced;
}
