import type { CaptureRequest } from "./protocol";

/**
 * The offline queue.
 *
 * Obsidian will not always be running when someone finds something worth keeping, and a capture tool that
 * quietly loses things in that moment is worse than no capture tool at all — the failure is invisible until
 * you go looking for something that was never saved. So a capture that can't be delivered is held and
 * retried rather than dropped.
 *
 * The logic is pure and the storage is injected, which keeps every rule here testable: what gets retried,
 * when it's given up on, and what happens when the same page is captured twice.
 */

export interface QueuedCapture {
  readonly id: string;
  readonly request: CaptureRequest;
  /** When it was first queued. */
  readonly queuedAt: number;
  readonly attempts: number;
  /** Why the last attempt failed, kept so the person can be told rather than left guessing. */
  readonly lastError?: string;
}

/** Stop retrying after this many failures — past which something is wrong that retrying won't fix. */
export const MAX_ATTEMPTS = 5;

/** Drop anything older than this. A week-old capture retried silently would be a surprise, not a service. */
export const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/** Cap the queue so a long offline stretch can't grow it without bound. */
export const MAX_QUEUED = 200;

export function enqueue(
  queue: readonly QueuedCapture[],
  request: CaptureRequest,
  now: number,
  id: string,
): QueuedCapture[] {
  const entry: QueuedCapture = { id, request, queuedAt: now, attempts: 0 };
  // Newest last: the queue drains in the order things were captured, which is the order they make sense in.
  return [...queue, entry].slice(-MAX_QUEUED);
}

/** The next capture to attempt, or null when there's nothing ready. */
export function next(queue: readonly QueuedCapture[]): QueuedCapture | null {
  return queue.find((q) => q.attempts < MAX_ATTEMPTS) ?? null;
}

export function remove(queue: readonly QueuedCapture[], id: string): QueuedCapture[] {
  return queue.filter((q) => q.id !== id);
}

/** Record a failed attempt. */
export function markFailed(queue: readonly QueuedCapture[], id: string, error: string): QueuedCapture[] {
  return queue.map((q) => (q.id === id ? { ...q, attempts: q.attempts + 1, lastError: error } : q));
}

/** Drop what's too old or has failed too often. Returns the survivors and what was discarded. */
export function prune(
  queue: readonly QueuedCapture[],
  now: number,
): { readonly kept: QueuedCapture[]; readonly dropped: QueuedCapture[] } {
  const kept: QueuedCapture[] = [];
  const dropped: QueuedCapture[] = [];
  for (const entry of queue) {
    const tooOld = now - entry.queuedAt > MAX_AGE_MS;
    const tooManyTries = entry.attempts >= MAX_ATTEMPTS;
    if (tooOld || tooManyTries) dropped.push(entry);
    else kept.push(entry);
  }
  return { kept, dropped };
}

/**
 * How long to wait before the next attempt.
 *
 * Backs off so a bridge that's simply switched off isn't hammered, but stays bounded — the common case is
 * "Obsidian isn't open yet", which resolves in minutes, not hours.
 */
export function retryDelayMs(attempts: number): number {
  const base = 15_000;
  const capped = Math.min(attempts, 4);
  return base * Math.pow(2, capped);
}

/** Whether two queued captures are the same page going to the same view — used to avoid stacking retries. */
export function isDuplicateOf(a: CaptureRequest, b: CaptureRequest): boolean {
  if (a.viewId !== b.viewId) return false;
  const urlA = (a.url ?? "").trim().toLowerCase();
  const urlB = (b.url ?? "").trim().toLowerCase();
  if (urlA !== "" && urlA === urlB) return true;
  // No URL to compare (a manual entry, say): fall back to the fields themselves.
  const key = (r: CaptureRequest): string =>
    r.fields.map((f) => `${f.key}=${f.value}`).sort().join("|");
  return urlA === "" && urlB === "" && key(a) === key(b);
}

/** Add a capture unless the same one is already waiting. */
export function enqueueUnique(
  queue: readonly QueuedCapture[],
  request: CaptureRequest,
  now: number,
  id: string,
): QueuedCapture[] {
  if (queue.some((q) => isDuplicateOf(q.request, request))) return [...queue];
  return enqueue(queue, request, now, id);
}
