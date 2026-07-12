import { describe, it, expect } from "vitest";
import { buildXlsx, DEFAULT_XLSX_OPTIONS } from "../src/services/export/export-format";
import { applyXlsxCellEdits, appendXlsxRows, deleteXlsxRows } from "../src/services/office/xlsx-writer";
import { openXlsxWorkbook } from "../src/services/office/xlsx-workbook";
import { openOfficePackage } from "../src/services/office/office-package";

const OPTS = { ...DEFAULT_XLSX_OPTIONS, sheetName: "Export", zebra: true };
const base = buildXlsx(
  {
    headers: ["Task", "Status", "Score"],
    rows: [
      ["A", "Todo", "3"],
      ["B", "Doing", "7"],
    ],
  },
  OPTS,
);

function readGrid(bytes: Uint8Array): string[][] {
  const wb = openXlsxWorkbook(bytes);
  const ref = wb.resolveSheet("Export")!;
  return wb.readSheet(ref).map((row) => row.map((c) => c.text));
}

describe("xlsx write-back", () => {
  it("edits a string cell in place and leaves the rest intact", () => {
    const result = applyXlsxCellEdits(base, [{ sheet: "Export", row: 2, headerRow: 0, column: "Status", value: "Done" }]);
    expect(result.applied).toBe(1);
    expect(result.failed).toBe(0);
    const grid = readGrid(result.bytes);
    expect(grid[0]).toEqual(["Task", "Status", "Score"]); // header untouched
    expect(grid[1]).toEqual(["A", "Done", "3"]); // Excel row 2 → Status changed
    expect(grid[2]).toEqual(["B", "Doing", "7"]); // other row untouched
  });

  it("writes a numeric value as a number", () => {
    const result = applyXlsxCellEdits(base, [{ sheet: "Export", row: 3, headerRow: 0, column: "Score", value: "42" }]);
    expect(readGrid(result.bytes)[2]).toEqual(["B", "Doing", "42"]);
  });

  it("applies several edits across rows in one pass", () => {
    const result = applyXlsxCellEdits(base, [
      { sheet: "Export", row: 2, headerRow: 0, column: "Task", value: "Alpha" },
      { sheet: "Export", row: 3, headerRow: 0, column: "Status", value: "Blocked" },
    ]);
    expect(result.applied).toBe(2);
    const grid = readGrid(result.bytes);
    expect(grid[1]![0]).toBe("Alpha");
    expect(grid[2]![1]).toBe("Blocked");
  });

  it("fills a previously-empty cell", () => {
    const sparse = buildXlsx({ headers: ["A", "B"], rows: [["x", ""]] }, OPTS);
    const result = applyXlsxCellEdits(sparse, [{ sheet: "Export", row: 2, headerRow: 0, column: "B", value: "filled" }]);
    expect(result.applied).toBe(1);
    expect(readGrid(result.bytes)[1]).toEqual(["x", "filled"]);
  });

  it("reports a failure for an unknown column rather than corrupting the file", () => {
    const result = applyXlsxCellEdits(base, [{ sheet: "Export", row: 2, headerRow: 0, column: "Nope", value: "x" }]);
    expect(result.applied).toBe(0);
    expect(result.failed).toBe(1);
    expect(readGrid(result.bytes)[1]).toEqual(["A", "Todo", "3"]); // unchanged
  });

  it("preserves the styles part after an edit (workbook not corrupted)", () => {
    const result = applyXlsxCellEdits(base, [{ sheet: "Export", row: 2, headerRow: 0, column: "Task", value: "Z" }]);
    // Re-reading succeeds (would throw if the zip/OOXML were broken) and other sheets/styles survive.
    expect(() => openXlsxWorkbook(result.bytes)).not.toThrow();
  });
});

describe("xlsx row append/delete", () => {
  it("appends a new row after the last, mapping values to columns", () => {
    const result = appendXlsxRows(base, [{ sheet: "Export", headerRow: 0, values: { Task: "C", Status: "New", Score: "9" } }]);
    expect(result.applied).toBe(1);
    const grid = readGrid(result.bytes);
    expect(grid).toHaveLength(4); // header + 3 data rows
    expect(grid[3]).toEqual(["C", "New", "9"]);
  });

  it("deletes a row and shifts the rows below it up", () => {
    const three = appendXlsxRows(base, [{ sheet: "Export", headerRow: 0, values: { Task: "C", Status: "New", Score: "9" } }]).bytes;
    // three now has rows: header, A(2), B(3), C(4). Delete B (row 3).
    const result = deleteXlsxRows(three, [{ sheet: "Export", row: 3 }]);
    expect(result.applied).toBe(1);
    const grid = readGrid(result.bytes);
    expect(grid).toHaveLength(3); // header + A + C
    expect(grid[1]).toEqual(["A", "Todo", "3"]);
    expect(grid[2]).toEqual(["C", "New", "9"]); // C shifted up from row 4 to row 3
  });

  it("deletes multiple rows at once, keeping the rest aligned", () => {
    const rows = { headers: ["N"], rows: [["1"], ["2"], ["3"], ["4"]] };
    const wb = buildXlsx(rows, OPTS);
    // rows: header(1), 1(2), 2(3), 3(4), 4(5). Delete rows 3 and 5 (values "2" and "4").
    const result = deleteXlsxRows(wb, [{ sheet: "Export", row: 3 }, { sheet: "Export", row: 5 }]);
    expect(result.applied).toBe(2);
    const grid = readGrid(result.bytes).map((r) => r[0]);
    expect(grid).toEqual(["N", "1", "3"]);
  });

  it("round-trips: append then re-read stays a valid workbook", () => {
    const result = appendXlsxRows(base, [{ sheet: "Export", headerRow: 0, values: { Task: "D" } }]);
    expect(() => openXlsxWorkbook(result.bytes)).not.toThrow();
  });
});

describe("xlsx formula protection", () => {
  it("refuses to overwrite a formula cell", () => {
    const pkg = openOfficePackage(base);
    const sheetPart = "xl/worksheets/sheet1.xml";
    const xml = pkg.readText(sheetPart)!.replace(/<c r="B2"[^>]*(?:\/>|>[\s\S]*?<\/c>)/, '<c r="B2"><f>LEN(A2)</f><v>1</v></c>');
    const withFormula = pkg.withPart(sheetPart, xml).toBytes();

    const result = applyXlsxCellEdits(withFormula, [{ sheet: "Export", row: 2, headerRow: 0, column: "Status", value: "X" }]);
    expect(result.applied).toBe(0);
    expect(result.failed).toBe(1);
    expect(openOfficePackage(result.bytes).readText(sheetPart)!).toContain("<f>LEN(A2)</f>");
  });
});

describe("xlsx boolean round-trip", () => {
  it("keeps a boolean cell boolean when toggled (writes TRUE/FALSE, not the text 'x')", () => {
    const pkg = openOfficePackage(base);
    const sheetPart = "xl/worksheets/sheet1.xml";
    const xml = pkg.readText(sheetPart)!.replace(/<c r="B2"[^>]*(?:\/>|>[\s\S]*?<\/c>)/, '<c r="B2" t="b"><v>0</v></c>');
    const wb = pkg.withPart(sheetPart, xml).toBytes();
    const result = applyXlsxCellEdits(wb, [{ sheet: "Export", row: 2, headerRow: 0, column: "Status", value: "x" }]);
    expect(result.applied).toBe(1);
    const out = openOfficePackage(result.bytes).readText(sheetPart)!;
    expect(out).toMatch(/<c r="B2"[^>]*t="b"[^>]*><v>1<\/v>/);
  });
});
