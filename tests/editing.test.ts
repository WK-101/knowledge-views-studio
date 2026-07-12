import { describe, it, expect } from "vitest";
import { createEditingHandlers } from "../src/views/editing";
import { WriterService } from "../src/services/write/writer-service";
import { tableExtractor, type Row, type SourceFileMeta } from "../src/domain/index";
import type { DataService, UndoManager } from "../src/services/index";
import { FakeVaultGateway } from "./_helpers";

function meta(path: string): SourceFileMeta {
  return {
    filePath: path,
    fileName: path.replace(/^.*\//, "").replace(/\.[^.]*$/, ""),
    folderPath: path.replace(/\/[^/]*$/, ""),
    createdMs: 0,
    modifiedMs: 1,
    sizeBytes: 0,
  };
}

const table = (status: string): string => ["| Title | Status |", "| --- | --- |", `| [[Row]] | ${status} |`].join("\n");
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 10));

function harness() {
  const gateway = new FakeVaultGateway();
  const writer = new WriterService(gateway);
  const undos: string[] = [];
  const dataService = { invalidate: () => {} } as unknown as DataService;
  const undo = { push: (e: { label: string }) => undos.push(e.label) } as unknown as UndoManager;
  const handlers = createEditingHandlers({ dataService, writer, undo }, () => {});
  return { gateway, handlers, undos };
}

describe("editing onBulkDelete", () => {
  it("deletes selected rows across multiple markdown files, undo-backed", async () => {
    const { gateway, handlers, undos } = harness();
    gateway.setFile("A/one.md", table("open"));
    gateway.setFile("B/two.md", table("done"));
    const rowA = tableExtractor.extract({ file: meta("A/one.md"), content: table("open") })[0]!;
    const rowB = tableExtractor.extract({ file: meta("B/two.md"), content: table("done") })[0]!;

    handlers.onBulkDelete([rowA, rowB]);
    await flush();

    expect(await gateway.read("A/one.md")).not.toContain("[[Row]]");
    expect(await gateway.read("B/two.md")).not.toContain("[[Row]]");
    expect(undos.some((l) => l.toLowerCase().includes("delete"))).toBe(true);
  });

  it("deletes markdown rows but leaves read-only (xlsx) rows and their file byte-untouched", async () => {
    const { gateway, handlers } = harness();
    gateway.setFile("A/one.md", table("open"));
    gateway.setBinary("Data/book.xlsx", new Uint8Array([1, 2, 3, 4]));
    const mdRow = tableExtractor.extract({ file: meta("A/one.md"), content: table("open") })[0]!;
    const xlsxRow: Row = {
      cells: { Name: "x" },
      file: meta("Data/book.xlsx"),
      provenance: { filePath: "Data/book.xlsx", extractor: "xlsx", locator: {}, fingerprint: "" },
    };

    handlers.onBulkDelete([mdRow, xlsxRow]);
    await flush();

    expect(await gateway.read("A/one.md")).not.toContain("[[Row]]"); // md row deleted
    const after = await gateway.readBinary("Data/book.xlsx");
    expect(Array.from(new Uint8Array(after))).toEqual([1, 2, 3, 4]); // xlsx never processed as text
  });
});

describe("editing error handling", () => {
  it("catches a thrown write error instead of leaving an unhandled rejection", async () => {
    // A writer whose snapshot rejects (e.g. a file vanished mid-edit).
    const throwingWriter = {
      snapshot: async () => {
        throw new Error("disk on fire");
      },
      editCells: async () => ({ applied: 0, failures: [] }),
    } as unknown as WriterService;
    const dataService = { invalidate: () => {} } as unknown as DataService;
    const undo = { push: () => {} } as unknown as UndoManager;
    const handlers = createEditingHandlers({ dataService, writer: throwingWriter, undo }, () => {});
    const row = tableExtractor.extract({ file: meta("A/x.md"), content: table("open") })[0]!;

    // Must not throw synchronously, and the rejection must be swallowed (vitest fails the run on any
    // unhandled rejection, so reaching the assertion below is itself the proof the error was caught).
    expect(() => handlers.onEditCell(row, "Status", "done")).not.toThrow();
    await flush();
    expect(true).toBe(true);
  });
});
