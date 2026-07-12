import { describe, it, expect } from "vitest";
import { parseMarkdownTables } from "../src/domain/extract/markdown-table";
import { tableExtractor } from "../src/domain/extract/table-extractor";
import { applyCellEdits } from "../src/services/write/source-writer";
import { escapeTableCell, splitTableRow } from "../src/util/markdown";

const file = { fileName: "N", filePath: "N.md", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
const read = (c: string) => tableExtractor.extract({ file, content: c, options: {} });

describe("code fences are documentation, not data", () => {
  it("a table inside a fenced block is not scraped as rows", () => {
    const md = ["# How to write a table", "", "```markdown", "| A | B |", "| --- | --- |", "| 1 | 2 |", "```", ""].join("\n");
    expect(parseMarkdownTables(md)).toHaveLength(0);
    expect(read(md)).toHaveLength(0);
  });

  it("tilde fences too, and fences with an info string", () => {
    const md = ["~~~md", "| A | B |", "| --- | --- |", "| 1 | 2 |", "~~~", ""].join("\n");
    expect(parseMarkdownTables(md)).toHaveLength(0);
  });

  it("a real table AFTER a fenced example is still found", () => {
    const md = [
      "```markdown",
      "| Example | Only |",
      "| --- | --- |",
      "| do not | index me |",
      "```",
      "",
      "| Task | Status |",
      "| --- | --- |",
      "| Real row | Todo |",
      "",
    ].join("\n");
    const tables = parseMarkdownTables(md);
    expect(tables).toHaveLength(1);
    expect(tables[0]!.headers).toEqual(["Task", "Status"]);
    expect(tables[0]!.rows[0]!.cells).toEqual(["Real row", "Todo"]);
  });

  it("an unterminated fence swallows the rest of the note (as Markdown itself does)", () => {
    const md = ["```", "| A | B |", "| --- | --- |", "| 1 | 2 |"].join("\n");
    expect(parseMarkdownTables(md)).toHaveLength(0);
  });
});

describe("escaped pipes round-trip exactly", () => {
  it("a cell's value is the real pipe, not the escape artifact", () => {
    expect(splitTableRow("| alt | a \\| b |")).toEqual(["alt", "a | b"]);
  });

  it("other backslashes are left alone (Windows paths, LaTeX, regex)", () => {
    expect(splitTableRow("| p | C:\\Users\\me |")).toEqual(["p", "C:\\Users\\me"]);
    expect(splitTableRow("| m | \\alpha + \\beta |")).toEqual(["m", "\\alpha + \\beta"]);
  });

  it("escape and unescape are inverses", () => {
    expect(splitTableRow(`| x | ${escapeTableCell("a | b")} |`)).toEqual(["x", "a | b"]);
  });

  it("typing a pipe into a cell does not split the row", () => {
    const content = ["| A | B |", "| --- | --- |", "| x | y |", ""].join("\n");
    const row = read(content)[0]!;
    const out = applyCellEdits(content, [{ provenance: row.provenance, column: "B", value: "a | b" }]);
    const back = read(out.content);
    expect(back).toHaveLength(1);
    expect(back[0]!.cells["B"]).toBe("a | b"); // survives read -> write -> read
    expect(back[0]!.cells["A"]).toBe("x");
  });

  it("editing one cell never disturbs an escaped pipe in another", () => {
    const content = ["| A | B |", "| --- | --- |", "| x | a \\| b |", ""].join("\n");
    const row = read(content)[0]!;
    const out = applyCellEdits(content, [{ provenance: row.provenance, column: "A", value: "z" }]);
    expect(out.content).toContain("a \\| b");
    expect(read(out.content)[0]!.cells["B"]).toBe("a | b");
  });
});
