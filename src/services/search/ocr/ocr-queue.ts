/**
 * A tiny work queue that runs one job at a time, in the browser's idle time, so OCR never competes with
 * typing or rendering. Jobs are keyed by file path (so a file re-saved before its job runs replaces the old
 * one), can be dropped on delete/rename, and high-priority jobs (the file you just opened) jump the line.
 *
 * Pure enough to unit-test: the scheduler is injected.
 */
export type IdleScheduler = (fn: () => void) => void;

/** requestIdleCallback where available, else a short timeout — both defer past the current frame. */
export function idleScheduler(win: Window): IdleScheduler {
  const ric = (win as unknown as { requestIdleCallback?: (fn: () => void, opts?: { timeout: number }) => void }).requestIdleCallback;
  if (typeof ric === "function") return (fn) => ric.call(win, fn, { timeout: 2000 });
  return (fn) => win.setTimeout(fn, 200);
}

interface Job {
  key: string;
  run: () => Promise<void>;
}

export class OcrQueue {
  private readonly high: Job[] = [];
  private readonly low: Job[] = [];
  private readonly keys = new Set<string>();
  private running = false;

  constructor(private readonly schedule: IdleScheduler) {}

  get size(): number {
    return this.high.length + this.low.length;
  }

  /** Enqueue (or replace) a job for `key`. Replacing keeps the newest closure. */
  push(key: string, run: () => Promise<void>, priority: "high" | "low"): void {
    this.drop(key);
    (priority === "high" ? this.high : this.low).push({ key, run });
    this.keys.add(key);
    this.pump();
  }

  /** Remove a pending job (file deleted/renamed/re-saved). A running job finishes. */
  drop(key: string): void {
    if (!this.keys.has(key)) return;
    const rm = (arr: Job[]): void => {
      const i = arr.findIndex((j) => j.key === key);
      if (i >= 0) arr.splice(i, 1);
    };
    rm(this.high);
    rm(this.low);
    this.keys.delete(key);
  }

  clear(): void {
    this.high.length = 0;
    this.low.length = 0;
    this.keys.clear();
  }

  private pump(): void {
    if (this.running) return;
    const job = this.high.shift() ?? this.low.shift();
    if (!job) return;
    this.keys.delete(job.key);
    this.running = true;
    this.schedule(() => {
      void job
        .run()
        .catch(() => undefined)
        .finally(() => {
          this.running = false;
          this.pump();
        });
    });
  }
}
