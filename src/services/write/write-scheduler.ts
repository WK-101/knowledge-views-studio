import type { Row, RowProvenance } from "../../domain/index";
import type { WriterService } from "./writer-service";
import type { UndoManager } from "../undo/undo-manager";

export type SaveStatus = "idle" | "saving" | "saved" | "error";

export interface WriteSchedulerDeps {
  readonly writer: WriterService;
  readonly invalidate: (path: string) => void;
  readonly undo: UndoManager;
  readonly rerender: () => void;
  readonly onStatus: (status: SaveStatus) => void;
  readonly notify: (message: string) => void;
  /** Debounce window before pending edits are written (ms). */
  readonly delayMs?: number;
}

interface PendingEdit {
  readonly provenance: RowProvenance;
  readonly column: string;
  value: string;
}

/**
 * Coalesces inline cell edits into batched, background writes, and overlays the pending values so the
 * grid updates instantly (even on scroll) before the write + re-read completes. This is what makes
 * editing — especially of large Excel workbooks — feel native: type, see it immediately, and the save
 * happens quietly a moment later, batching rapid edits into a single write and a single undo step.
 */
export class WriteScheduler {
  private readonly pending = new Map<string, PendingEdit>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private flushing = false;

  constructor(private readonly deps: WriteSchedulerDeps) {}

  private key(prov: RowProvenance, column: string): string {
    return `${prov.fingerprint}\u0000${column.toLowerCase()}`;
  }

  get hasPending(): boolean {
    return this.pending.size > 0 || this.flushing;
  }

  /** Overlay any pending edits for this row (optimistic display). */
  overlay = (row: Row): Row => {
    if (this.pending.size === 0) return row;
    let cells: Record<string, string> | null = null;
    for (const edit of this.pending.values()) {
      if (edit.provenance.fingerprint === row.provenance.fingerprint) {
        cells = cells ?? { ...row.cells };
        cells[edit.column] = edit.value;
      }
    }
    return cells ? { ...row, cells } : row;
  };

  /** Queue a cell edit; the actual write is debounced and batched. */
  queue(provenance: RowProvenance, column: string, value: string): void {
    this.pending.set(this.key(provenance, column), { provenance, column, value });
    this.deps.onStatus("saving");
    this.schedule();
    this.deps.rerender(); // optimistic: show the new value now
  }

  private schedule(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.flush(), this.deps.delayMs ?? 600);
  }

  /** Write all currently-pending edits as one batch. Edits queued during the flush are kept. */
  async flush(): Promise<void> {
    if (this.flushing) return;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.pending.size === 0) return;
    this.flushing = true;

    const keys = [...this.pending.keys()];
    const edits = keys.map((k) => this.pending.get(k)!);
    const paths = [...new Set(edits.map((e) => e.provenance.filePath))];

    try {
      const snapshot = await this.deps.writer.snapshot(paths);
      const result = await this.deps.writer.editCells(
        edits.map((e) => ({ provenance: e.provenance, column: e.column, value: e.value })),
      );
      for (const k of keys) this.pending.delete(k); // only clear what we flushed

      if (result.applied > 0) {
        this.deps.undo.push({
          label: `Edit ${result.applied} cell${result.applied === 1 ? "" : "s"}`,
          undo: async () => {
            await this.deps.writer.restore(snapshot);
            for (const p of paths) this.deps.invalidate(p);
            this.deps.rerender();
          },
        });
      }
      for (const p of paths) this.deps.invalidate(p);
      if (result.failures.length > 0) {
        this.deps.notify(`${result.failures.length} cell(s) couldn't be saved.`);
        this.deps.onStatus("error");
      } else {
        this.deps.onStatus("saved");
      }
    } catch (error) {
      for (const k of keys) this.pending.delete(k);
      for (const p of paths) this.deps.invalidate(p);
      this.deps.notify(`Couldn't save: ${error instanceof Error ? error.message : "unexpected error"}`);
      this.deps.onStatus("error");
    } finally {
      this.flushing = false;
      this.deps.rerender();
      if (this.pending.size > 0) this.schedule(); // edits arrived mid-flush
    }
  }

  /** Flush synchronously, waiting for any in-flight write (e.g. when the view closes). */
  async flushNow(): Promise<void> {
    while (this.flushing) await new Promise((r) => setTimeout(r, 15));
    await this.flush();
  }
}
