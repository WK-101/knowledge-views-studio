import { describe, it, expect } from "vitest";
import { WriterService } from "../src/services/write/writer-service";
import { tableExtractor, type SourceFileMeta } from "../src/domain/index";
import { FakeVaultGateway } from "./_helpers";

function meta(path: string): SourceFileMeta {
  return {
    filePath: path,
    fileName: path.replace(/^.*\//, "").replace(/\.md$/, ""),
    folderPath: path.replace(/\/[^/]*$/, ""),
    createdMs: 0,
    modifiedMs: 1,
    sizeBytes: 0,
  };
}

const tableFor = (status: string): string =>
  ["| Title | Status |", "| --- | --- |", `| [[Row]] | ${status} |`].join("\n");

describe("WriterService.editCells", () => {
  it("writes back across multiple files via the gateway, one process per file", async () => {
    const gateway = new FakeVaultGateway();
    gateway.setFile("A/one.md", tableFor("open"));
    gateway.setFile("B/two.md", tableFor("open"));
    const writer = new WriterService(gateway);

    const rowA = tableExtractor.extract({ file: meta("A/one.md"), content: tableFor("open") })[0]!;
    const rowB = tableExtractor.extract({ file: meta("B/two.md"), content: tableFor("open") })[0]!;

    const result = await writer.editCells([
      { provenance: rowA.provenance, column: "Status", value: "done" },
      { provenance: rowB.provenance, column: "Status", value: "blocked" },
    ]);

    expect(result.applied).toBe(2);
    expect(result.failures).toEqual([]);
    expect(await gateway.read("A/one.md")).toContain("| [[Row]] | done |");
    expect(await gateway.read("B/two.md")).toContain("| [[Row]] | blocked |");
  });

  it("aggregates failures for unknown columns", async () => {
    const gateway = new FakeVaultGateway();
    gateway.setFile("A/one.md", tableFor("open"));
    const writer = new WriterService(gateway);
    const row = tableExtractor.extract({ file: meta("A/one.md"), content: tableFor("open") })[0]!;

    const result = await writer.editCells([{ provenance: row.provenance, column: "Ghost", value: "x" }]);
    expect(result.applied).toBe(0);
    expect(result.failures).toHaveLength(1);
  });
});

describe("WriterService row operations", () => {
  it("deletes and appends rows", async () => {
    const gateway = new FakeVaultGateway();
    gateway.setFile("A/one.md", tableFor("open"));
    const writer = new WriterService(gateway);
    const row = tableExtractor.extract({ file: meta("A/one.md"), content: tableFor("open") })[0]!;

    const appended = await writer.appendRow(row.provenance, { Title: "[[New]]", Status: "todo" });
    expect(appended.ok).toBe(true);
    expect(await gateway.read("A/one.md")).toContain("| [[New]] | todo |");

    const deleted = await writer.deleteRows([row.provenance]);
    expect(deleted.ok).toBe(true);
    expect(await gateway.read("A/one.md")).not.toContain("[[Row]]");
  });
});
