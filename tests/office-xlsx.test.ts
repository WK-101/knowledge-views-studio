import { describe, it, expect } from "vitest";
import { zipSync, strToU8, unzipSync, strFromU8 } from "fflate";
import { openOfficePackage } from "../src/services/office/office-package";
import { openXlsxWorkbook, columnToIndex, indexToColumn, serialToIso } from "../src/services/office/xlsx-workbook";
import { xlsxExtractor } from "../src/services/office/xlsx-extractor";
import { buildXlsx, buildExportTable } from "../src/services/export/export-format";
import type { Row } from "../src/domain/index";

const ab = (u: Uint8Array): ArrayBuffer => { const b = new ArrayBuffer(u.byteLength); new Uint8Array(b).set(u); return b; };

// A hand-built workbook exercising shared strings, a date-styled serial, a formula, sparse cells,
// and two sheets — none of which our exporter produces.
function fixtureXlsx(): Uint8Array {
  const workbook =
    `<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
    `<workbookPr date1904="0"/>` +
    `<sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Other" sheetId="2" r:id="rId2"/></sheets></workbook>`;
  const rels =
    `<Relationships>` +
    `<Relationship Id="rId1" Target="worksheets/sheet1.xml"/>` +
    `<Relationship Id="rId2" Target="worksheets/sheet2.xml"/>` +
    `<Relationship Id="rId3" Target="sharedStrings.xml"/>` +
    `<Relationship Id="rId4" Target="styles.xml"/>` +
    `</Relationships>`;
  const shared = `<sst><si><t>Title</t></si><si><t>Score</t></si><si><t>Due</t></si><si><t>Dune</t></si></sst>`;
  const styles = `<styleSheet><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>`;
  const sheet1 =
    `<worksheet><sheetData>` +
    `<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="inlineStr"><is><t>Total</t></is></c></row>` +
    `<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"><v>5</v></c><c r="C2" s="1"><v>44197</v></c><c r="D2"><f>B2*2</f><v>10</v></c></row>` +
    `<row r="3"><c r="A3" t="inlineStr"><is><t>Gap</t></is></c><c r="D3"><v>7</v></c></row>` +
    `</sheetData></worksheet>`;
  const sheet2 = `<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Secondary</t></is></c></row></sheetData></worksheet>`;
  return zipSync({
    "xl/workbook.xml": strToU8(workbook),
    "xl/_rels/workbook.xml.rels": strToU8(rels),
    "xl/sharedStrings.xml": strToU8(shared),
    "xl/styles.xml": strToU8(styles),
    "xl/worksheets/sheet1.xml": strToU8(sheet1),
    "xl/worksheets/sheet2.xml": strToU8(sheet2),
  });
}

describe("OfficePackage", () => {
  it("reads parts and replaces one while keeping the others byte-identical", () => {
    const pkg = openOfficePackage(fixtureXlsx());
    expect(pkg.has("xl/workbook.xml")).toBe(true);
    expect(pkg.readText("xl/sharedStrings.xml")).toContain("Dune");

    const before = unzipSync(fixtureXlsx());
    const swapped = pkg.withPart("xl/worksheets/sheet1.xml", "<worksheet/>").toBytes();
    const after = unzipSync(swapped);
    // Untouched parts keep identical decompressed bytes.
    for (const part of ["xl/workbook.xml", "xl/sharedStrings.xml", "xl/styles.xml", "xl/worksheets/sheet2.xml"]) {
      expect(Array.from(after[part]!), part).toEqual(Array.from(before[part]!));
    }
    expect(strFromU8(after["xl/worksheets/sheet1.xml"]!)).toBe("<worksheet/>");
  });

  it("throws a readable error for bytes that aren't a zip (e.g. an old .xls or empty file)", () => {
    expect(() => openOfficePackage(new Uint8Array([0xd0, 0xcf, 0x11, 0xe0]))).toThrow(/zip signature/);
    expect(() => openOfficePackage(new Uint8Array(0))).toThrow(/zip signature/);
  });
});

describe("bijective base-26 columns", () => {
  it("maps letters ↔ indices correctly (AA is not A*26)", () => {
    expect(["A", "Z", "AA", "AZ", "BA"].map(columnToIndex)).toEqual([0, 25, 26, 51, 52]);
    expect([0, 25, 26, 51, 52].map(indexToColumn)).toEqual(["A", "Z", "AA", "AZ", "BA"]);
  });
});

describe("date serials", () => {
  it("converts 1900-system serials to ISO dates", () => {
    expect(serialToIso(44197, false)).toBe("2021-01-01");
    expect(serialToIso(1, false)).toBe("1900-01-01");
  });
});

describe("XlsxWorkbook.readSheet", () => {
  const wb = openXlsxWorkbook(fixtureXlsx());

  it("lists sheets and resolves by name / index / default", () => {
    expect(wb.sheets().map((s) => s.name)).toEqual(["Data", "Other"]);
    expect(wb.resolveSheet()?.name).toBe("Data"); // default first
    expect(wb.resolveSheet("Other")?.name).toBe("Other");
    expect(wb.resolveSheet(2)?.name).toBe("Other"); // 1-based
    expect(wb.resolveSheet("Nope")).toBeUndefined();
  });

  it("reads a dense, reference-aligned grid with strings, numbers, dates and formulas", () => {
    const grid = wb.readSheet(wb.resolveSheet("Data")!);
    // header row
    expect(grid[0]!.map((c) => c.text)).toEqual(["Title", "Score", "Due", "Total"]);
    // data row: shared string, number, date (ISO), formula (cached value + flagged)
    expect(grid[1]![0]!.text).toBe("Dune");
    expect(grid[1]![1]!).toMatchObject({ text: "5", kind: "number" });
    expect(grid[1]![2]!).toMatchObject({ text: "2021-01-01", kind: "date" });
    expect(grid[1]![3]!).toMatchObject({ text: "10", kind: "formula", isFormula: true });
    // sparse row: B3/C3 omitted in xml → dense grid pads them empty, D3 stays at column 3
    expect(grid[2]!.map((c) => c.text)).toEqual(["Gap", "", "", "7"]);
  });
});

function wbWith(sheet1Body: string, extra: { styles?: string; shared?: string } = {}): Uint8Array {
  const workbook =
    `<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
    `<sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>`;
  const rels =
    `<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>`;
  const parts: Record<string, Uint8Array> = {
    "xl/workbook.xml": strToU8(workbook),
    "xl/_rels/workbook.xml.rels": strToU8(rels),
    "xl/worksheets/sheet1.xml": strToU8(`<worksheet><sheetData>${sheet1Body}</sheetData></worksheet>`),
  };
  if (extra.shared) parts["xl/sharedStrings.xml"] = strToU8(extra.shared);
  if (extra.styles) parts["xl/styles.xml"] = strToU8(extra.styles);
  return zipSync(parts);
}

describe("XlsxWorkbook robustness", () => {
  const meta2 = { filePath: "S.xlsx", fileName: "S", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };

  it("finds the header even when the data starts below row 1", () => {
    const body =
      `<row r="3"><c r="A3" t="inlineStr"><is><t>Name</t></is></c><c r="B3" t="inlineStr"><is><t>Qty</t></is></c></row>` +
      `<row r="4"><c r="A4" t="inlineStr"><is><t>Bolt</t></is></c><c r="B4"><v>12</v></c></row>`;
    const wb = openXlsxWorkbook(wbWith(body));
    expect(wb.readSheet(wb.resolveSheet()!)[0]!.map((c) => c.text)).toEqual(["Name", "Qty"]);
    const rows = xlsxExtractor.extract({ file: meta2, content: "", bytes: ab(wbWith(body)) });
    expect(rows).toHaveLength(1);
    expect(rows[0]!.cells).toMatchObject({ Name: "Bolt", Qty: "12" });
    expect(rows[0]!.provenance.locator).toMatchObject({ row: 4 }); // the real Excel row, not a grid index
  });

  it("does not materialise the gap up to a stray far-down cell", () => {
    const body =
      `<row r="1"><c r="A1" t="inlineStr"><is><t>H</t></is></c></row>` +
      `<row r="2"><c r="A2" t="inlineStr"><is><t>x</t></is></c></row>` +
      `<row r="100000"><c r="A100000"><v>9</v></c></row>`;
    const wb = openXlsxWorkbook(wbWith(body));
    expect(wb.readSheet(wb.resolveSheet()!).length).toBe(3); // three content rows, not 100000
  });

  it("renders percent-formatted cells as percentages", () => {
    const styles = `<styleSheet><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="9"/></cellXfs></styleSheet>`;
    const body =
      `<row r="1"><c r="A1" t="inlineStr"><is><t>Rate</t></is></c></row>` +
      `<row r="2"><c r="A2" s="1"><v>0.1</v></c></row>` +
      `<row r="3"><c r="A3" s="1"><v>0.125</v></c></row>`;
    const wb = openXlsxWorkbook(wbWith(body, { styles }));
    const grid = wb.readSheet(wb.resolveSheet()!);
    expect(grid[1]![0]!.text).toBe("10%");
    expect(grid[2]![0]!.text).toBe("12.5%");
  });

  it("concatenates rich-text runs in a shared string", () => {
    const shared = `<sst><si><r><t>Hello </t></r><r><t>world</t></r></si></sst>`;
    const body = `<row r="1"><c r="A1" t="s"><v>0</v></c></row>`;
    const wb = openXlsxWorkbook(wbWith(body, { shared }));
    expect(wb.readSheet(wb.resolveSheet()!)[0]![0]!.text).toBe("Hello world");
  });

  it("disambiguates duplicate headers instead of collapsing them", () => {
    const body =
      `<row r="1"><c r="A1" t="inlineStr"><is><t>Name</t></is></c><c r="B1" t="inlineStr"><is><t>Name</t></is></c></row>` +
      `<row r="2"><c r="A2" t="inlineStr"><is><t>left</t></is></c><c r="B2" t="inlineStr"><is><t>right</t></is></c></row>`;
    const rows = xlsxExtractor.extract({ file: meta2, content: "", bytes: ab(wbWith(body)) });
    expect(rows[0]!.cells).toMatchObject({ Name: "left", "Name (2)": "right" });
  });
});

describe("xlsx extractor", () => {
  const meta = { filePath: "Book.xlsx", fileName: "Book", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };

  it("turns sheet rows into Rows with headers as columns + a locator", () => {
    const rows: Row[] = xlsxExtractor.extract({ file: meta, content: "", bytes: ab(fixtureXlsx()) });
    expect(rows).toHaveLength(2); // two data rows under the header
    expect(rows[0]!.cells).toMatchObject({ Title: "Dune", Score: "5", Due: "2021-01-01", Total: "10" });
    expect(rows[0]!.provenance).toMatchObject({ extractor: "xlsx", locator: { sheet: "Data", row: 2, headerRow: 0 } });
    expect(rows[1]!.cells).toMatchObject({ Title: "Gap", Total: "7" });
  });

  it("round-trips our own exporter's output (export → read back)", () => {
    const table = buildExportTable(
      [{ cells: { Name: "Ada", Age: "36" }, file: meta, provenance: { filePath: "x", extractor: "table", locator: {}, fingerprint: "" } }],
      [{ name: "Name", label: "Name", typeId: "text" }, { name: "Age", label: "Age", typeId: "number" }],
      false,
    );
    const rows = xlsxExtractor.extract({ file: meta, content: "", bytes: ab(buildXlsx(table)) });
    expect(rows).toHaveLength(1);
    expect(rows[0]!.cells).toMatchObject({ Name: "Ada", Age: "36" });
  });

  it("combines every sheet when sheet is 'all', tagging rows with a Sheet column", () => {
    const workbook =
      `<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
      `<sheets><sheet name="Alpha" sheetId="1" r:id="rId1"/><sheet name="Beta" sheetId="2" r:id="rId2"/></sheets></workbook>`;
    const rels =
      `<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>`;
    const s1 =
      `<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Name</t></is></c></row>` +
      `<row r="2"><c r="A2" t="inlineStr"><is><t>a1</t></is></c></row></sheetData></worksheet>`;
    const s2 =
      `<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Name</t></is></c></row>` +
      `<row r="2"><c r="A2" t="inlineStr"><is><t>b1</t></is></c></row>` +
      `<row r="3"><c r="A3" t="inlineStr"><is><t>b2</t></is></c></row></sheetData></worksheet>`;
    const bytes = zipSync({
      "xl/workbook.xml": strToU8(workbook),
      "xl/_rels/workbook.xml.rels": strToU8(rels),
      "xl/worksheets/sheet1.xml": strToU8(s1),
      "xl/worksheets/sheet2.xml": strToU8(s2),
    });
    const rows = xlsxExtractor.extract({ file: meta, content: "", bytes: ab(bytes), options: { sheet: "all" } });
    expect(rows.map((r) => r.cells.Name)).toEqual(["a1", "b1", "b2"]); // 1 from Alpha + 2 from Beta
    expect(rows.map((r) => r.cells.Sheet)).toEqual(["Alpha", "Beta", "Beta"]);

    const beta = xlsxExtractor.extract({ file: meta, content: "", bytes: ab(bytes), options: { sheet: "Beta" } });
    expect(beta.map((r) => r.cells.Name)).toEqual(["b1", "b2"]);
    expect(beta[0]!.cells.Sheet).toBeUndefined(); // single-sheet selection adds no Sheet column
  });

  it("returns nothing without bytes (binary-only)", () => {
    expect(xlsxExtractor.extract({ file: meta, content: "" })).toEqual([]);
  });

  it("flags Excel formula cells as read-only fields (so edits can't clobber them)", () => {
    const meta = { filePath: "F.xlsx", fileName: "F", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
    const body =
      `<row r="1"><c r="A1" t="inlineStr"><is><t>Item</t></is></c><c r="B1" t="inlineStr"><is><t>Total</t></is></c></row>` +
      `<row r="2"><c r="A2" t="inlineStr"><is><t>Widget</t></is></c><c r="B2"><f>A2*2</f><v>10</v></c></row>`;
    const rows = xlsxExtractor.extract({ file: meta, content: "", bytes: ab(wbWith(body)) });
    expect(rows).toHaveLength(1);
    expect(rows[0]!.cells).toEqual({ Item: "Widget", Total: "10" });
    expect(rows[0]!.provenance.readOnlyFields).toEqual(["Total"]);
  });

  it("formats currency cells with their symbol", () => {
    const meta = { filePath: "C.xlsx", fileName: "C", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };
    const styles =
      `<styleSheet><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;$&quot;#,##0.00"/></numFmts>` +
      `<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs></styleSheet>`;
    const body =
      `<row r="1"><c r="A1" t="inlineStr"><is><t>Price</t></is></c></row>` +
      `<row r="2"><c r="A2" s="1"><v>1234.5</v></c></row>`;
    const rows = xlsxExtractor.extract({ file: meta, content: "", bytes: ab(wbWith(body, { styles })) });
    expect(rows[0]!.cells.Price).toBe("$1,234.50");
  });
});