import { describe, it, expect } from "vitest";
import {
  parseCsv,
  parseMarkdownTable,
  parseXlsx,
  normalizeTable,
  detectImportFormat,
} from "../src/services/import/import-format";
import { buildXlsx, type ExportTable } from "../src/services/export/export-format";

describe("parseCsv", () => {
  it("handles quotes, escaped quotes, commas and newlines in fields", () => {
    const csv = 'Task,Notes\r\n"Write, test","He said ""hi"""\r\nShip,"line1\nline2"';
    const t = parseCsv(csv);
    expect(t.headers).toEqual(["Task", "Notes"]);
    expect(t.rows[0]).toEqual(["Write, test", 'He said "hi"']);
    expect(t.rows[1]).toEqual(["Ship", "line1\nline2"]);
  });
  it("skips fully-empty lines", () => {
    const t = parseCsv("A,B\n\n1,2\n");
    expect(t.rows).toEqual([["1", "2"]]);
  });
});

describe("parseMarkdownTable", () => {
  it("parses the first pipe table and unescapes pipes", () => {
    const md = "intro\n\n| Task | Owner |\n| --- | --- |\n| a\\|b | Sam |\n| c | Lee |\n\nafter";
    const t = parseMarkdownTable(md);
    expect(t.headers).toEqual(["Task", "Owner"]);
    expect(t.rows).toEqual([["a|b", "Sam"], ["c", "Lee"]]);
  });
  it("returns empty when no table present", () => {
    expect(parseMarkdownTable("just text").headers).toEqual([]);
  });
});

describe("parseXlsx round-trips buildXlsx", () => {
  it("recovers headers and rows, numbers and text", () => {
    const original: ExportTable = {
      headers: ["Task", "Count", "Notes"],
      rows: [
        ["Write", "42", 'say "hi"'],
        ["Ship", "7", ""],
      ],
    };
    const parsed = parseXlsx(buildXlsx(original));
    expect(parsed.headers).toEqual(original.headers);
    expect(parsed.rows).toEqual(original.rows);
  });
});

describe("normalizeTable", () => {
  it("pads/truncates rows and fills blank headers", () => {
    const t = normalizeTable({ headers: ["A", ""], rows: [["1"], ["1", "2", "3"]] });
    expect(t.headers).toEqual(["A", "Column 2"]);
    expect(t.rows).toEqual([["1", ""], ["1", "2"]]);
  });
});

describe("detectImportFormat", () => {
  it("maps extensions", () => {
    expect(detectImportFormat("data.CSV")).toBe("csv");
    expect(detectImportFormat("notes.md")).toBe("markdown");
    expect(detectImportFormat("book.xlsx")).toBe("xlsx");
  });
});
