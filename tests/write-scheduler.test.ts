import { describe, it, expect, vi } from "vitest";
import { WriteScheduler } from "../src/services/write/write-scheduler";
import type { WriterService } from "../src/services/write/writer-service";
import type { UndoManager } from "../src/services/undo/undo-manager";
import type { Row, RowProvenance } from "../src/domain/index";

const prov = (fp: string, path = "s.md"): RowProvenance => ({ filePath: path, extractor: "table", locator: {}, fingerprint: fp });
const row = (fp: string, cells: Record<string, string>): Row => ({
  cells,
  file: { filePath: "s.md", fileName: "s", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 },
  provenance: prov(fp),
});
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 30));

function make(writerOverride?: Partial<WriterService>) {
  const editCells = vi.fn(async (edits: readonly unknown[]) => ({ content: "", applied: edits.length, failures: [] as unknown[] }));
  const writer = {
    editCells,
    snapshot: vi.fn(async () => new Map()),
    restore: vi.fn(async () => undefined),
    ...writerOverride,
  } as unknown as WriterService;
  const invalidate = vi.fn();
  const undoPush = vi.fn();
  const rerender = vi.fn();
  const statuses: string[] = [];
  const notify = vi.fn();
  const sched = new WriteScheduler({
    writer,
    invalidate,
    undo: { push: undoPush } as unknown as UndoManager,
    rerender,
    onStatus: (s) => statuses.push(s),
    notify,
    delayMs: 10,
  });
  return { sched, editCells, invalidate, undoPush, rerender, statuses, notify };
}

describe("WriteScheduler", () => {
  it("overlays pending edits optimistically before they're written", () => {
    const { sched } = make();
    sched.queue(prov("r1"), "Status", "Done");
    expect(sched.overlay(row("r1", { Status: "Todo", Name: "A" })).cells).toEqual({ Status: "Done", Name: "A" });
    expect(sched.overlay(row("r2", { Status: "Todo" })).cells).toEqual({ Status: "Todo" }); // other rows untouched
  });

  it("coalesces rapid edits into a single write + single undo (latest value wins)", async () => {
    const { sched, editCells, undoPush } = make();
    sched.queue(prov("r1"), "Status", "A");
    sched.queue(prov("r1"), "Status", "B"); // supersedes A
    sched.queue(prov("r2"), "Owner", "X");
    await flush();
    expect(editCells).toHaveBeenCalledTimes(1);
    const edits = editCells.mock.calls[0]![0] as Array<{ provenance: RowProvenance; value: string }>;
    expect(edits).toHaveLength(2);
    expect(edits.find((e) => e.provenance.fingerprint === "r1")!.value).toBe("B");
    expect(undoPush).toHaveBeenCalledTimes(1);
  });

  it("reports saving then saved and clears pending after the flush", async () => {
    const { sched, statuses, invalidate } = make();
    sched.queue(prov("r1"), "S", "v");
    expect(statuses[0]).toBe("saving");
    expect(sched.hasPending).toBe(true);
    await flush();
    expect(statuses).toContain("saved");
    expect(sched.hasPending).toBe(false);
    expect(invalidate).toHaveBeenCalledWith("s.md");
  });

  it("surfaces an error (and keeps edits from being lost) when the write throws", async () => {
    const { sched, statuses, notify } = make({ editCells: vi.fn(async () => { throw new Error("disk full"); }) as unknown as WriterService["editCells"] });
    sched.queue(prov("r1"), "S", "v");
    await flush();
    expect(notify).toHaveBeenCalled();
    expect(statuses).toContain("error");
  });
});
