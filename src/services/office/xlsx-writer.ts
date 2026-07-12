import { openOfficePackage } from "./office-package";
import { columnToIndex, openXlsxWorkbook, type XlsxCell } from "./xlsx-workbook";
import { toBoolean } from "../../domain/columns/types/checkbox";

/**
 * Write-back for `.xlsx` sources. Given the original workbook bytes and a set of cell edits, this
 * surgically rewrites only the target cells in the target worksheet's XML and re-zips. Everything the
 * writer doesn't touch — other sheets, styles, formulas, number formats, charts, pivot caches — keeps
 * its exact bytes (see OfficePackage's fidelity guarantee), so an edit never corrupts the workbook.
 *
 * A cell is written as a number when its new value is numeric, otherwise as an inline string (so we
 * never have to manage the shared-strings pool). The cell's existing style index is preserved.
 */
export interface XlsxCellEdit {
  readonly sheet: string;
  readonly row: number; // 1-based Excel row number of the data cell
  readonly headerRow: number; // 0-based grid index of the header row
  readonly column: string; // column name as shown (disambiguated header)
  readonly value: string;
}

export interface XlsxWriteResult {
  readonly bytes: Uint8Array;
  readonly applied: number;
  readonly failed: number;
}

const NUMERIC = /^-?\d+(?:\.\d+)?$/;

function xmlEscape(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function refCol(ref: string): string {
  return ref.replace(/\d+$/, "");
}
function refRow(ref: string): number {
  return Number(/\d+$/.exec(ref)?.[0] ?? "0");
}

function buildCell(ref: string, value: string, styleAttr: string | undefined): string {
  const s = styleAttr ? ` s="${styleAttr}"` : "";
  if (value === "") return `<c r="${ref}"${s}/>`;
  if (NUMERIC.test(value)) return `<c r="${ref}"${s}><v>${value}</v></c>`;
  return `<c r="${ref}"${s} t="inlineStr"><is><t xml:space="preserve">${xmlEscape(value)}</t></is></c>`;
}

/** A boolean cell (`t="b"`), so toggling a checkbox on an Excel boolean column keeps it a real
 *  TRUE/FALSE value rather than turning it into the text "x". */
function buildBoolCell(ref: string, value: string, styleAttr: string | undefined): string {
  const s = styleAttr ? ` s="${styleAttr}"` : "";
  return `<c r="${ref}"${s} t="b"><v>${toBoolean(value) ? 1 : 0}</v></c>`;
}

/** Map each (disambiguated) header name to its column letter, mirroring the extractor's naming. */
function columnMap(headerCells: readonly XlsxCell[]): Map<string, string> {
  const counts = new Map<string, number>();
  const map = new Map<string, string>();
  for (const cell of headerCells) {
    const h = cell.text.trim();
    if (h === "") continue;
    const seen = counts.get(h) ?? 0;
    counts.set(h, seen + 1);
    const name = seen === 0 ? h : `${h} (${seen + 1})`;
    map.set(name.toLowerCase(), refCol(cell.ref));
  }
  return map;
}

/** Insert a `<c>` into a row's inner XML at the position dictated by its column. */
function insertCellByColumn(inner: string, ref: string, cellXml: string): string {
  const target = columnToIndex(refCol(ref));
  for (const m of inner.matchAll(/<c\b[^>]*\br="([A-Z]+)\d+"[^>]*(?:\/>|>[\s\S]*?<\/c>)/g)) {
    if (columnToIndex(m[1] ?? "") > target) return inner.slice(0, m.index) + cellXml + inner.slice(m.index);
  }
  return inner + cellXml;
}

/** Set one cell's value in a worksheet's XML. Returns null if the row can't be located. */
function setCellValue(xml: string, ref: string, value: string): string | null {
  const rowNum = refRow(ref);
  const rowMatch = new RegExp(`<row(?=[^>]*\\br="${rowNum}")[^>]*>[\\s\\S]*?</row>`).exec(xml);
  if (!rowMatch) return null;
  const rowXml = rowMatch[0];
  const gt = rowXml.indexOf(">");
  const open = rowXml.slice(0, gt + 1);
  const inner = rowXml.slice(gt + 1, rowXml.length - "</row>".length);

  const cellMatch = new RegExp(`<c(?=[^>]*\\br="${ref}")[^>]*(?:/>|>[\\s\\S]*?</c>)`).exec(inner);
  let nextInner: string;
  if (cellMatch) {
    if (/<f[\s>/]/.test(cellMatch[0])) return null; // never overwrite a formula cell with a literal
    const styleAttr = /\bs="([^"]*)"/.exec(cellMatch[0])?.[1];
    const wasBoolean = /\bt="b"/.test(cellMatch[0]);
    const cell = wasBoolean ? buildBoolCell(ref, value, styleAttr) : buildCell(ref, value, styleAttr);
    nextInner = inner.slice(0, cellMatch.index) + cell + inner.slice(cellMatch.index + cellMatch[0].length);
  } else {
    nextInner = insertCellByColumn(inner, ref, buildCell(ref, value, undefined));
  }
  return xml.slice(0, rowMatch.index) + open + nextInner + "</row>" + xml.slice(rowMatch.index + rowXml.length);
}

export function applyXlsxCellEdits(bytes: Uint8Array, edits: readonly XlsxCellEdit[]): XlsxWriteResult {
  const pkg = openOfficePackage(bytes);
  const wb = openXlsxWorkbook(bytes);

  const bySheet = new Map<string, XlsxCellEdit[]>();
  for (const edit of edits) {
    const list = bySheet.get(edit.sheet);
    if (list) list.push(edit);
    else bySheet.set(edit.sheet, [edit]);
  }

  let out = pkg;
  let applied = 0;
  let failed = 0;
  for (const [sheetName, sheetEdits] of bySheet) {
    const ref = wb.resolveSheet(sheetName);
    const xml = ref ? pkg.readText(ref.part) : undefined;
    if (!ref || xml == null) {
      failed += sheetEdits.length;
      continue;
    }
    const grid = wb.readSheet(ref);
    const byField = columnMap(grid[sheetEdits[0]!.headerRow] ?? []);
    let current = xml;
    for (const edit of sheetEdits) {
      const col = byField.get(edit.column.toLowerCase());
      const next = col ? setCellValue(current, `${col}${edit.row}`, edit.value) : null;
      if (next == null) {
        failed++;
        continue;
      }
      current = next;
      applied++;
    }
    out = out.withPart(ref.part, current);
  }
  return { bytes: applied > 0 ? out.toBytes() : bytes, applied, failed };
}

// ---- Row append / delete ---------------------------------------------------

export interface XlsxRowAppend {
  readonly sheet: string;
  readonly headerRow: number; // 0-based grid index of the header row
  readonly values: Readonly<Record<string, string>>; // column name → value
}

export interface XlsxRowDelete {
  readonly sheet: string;
  readonly row: number; // 1-based Excel row number
}

function buildRowXml(rowNum: number, byCol: Map<string, string>): string {
  const cols = [...byCol.keys()].sort((a, b) => columnToIndex(a) - columnToIndex(b));
  const cells = cols.map((c) => buildCell(`${c}${rowNum}`, byCol.get(c) ?? "", undefined)).join("");
  return `<row r="${rowNum}">${cells}</row>`;
}

/** Highest existing Excel row number in a worksheet's XML (0 if none). */
function maxRowNumber(xml: string): number {
  let max = 0;
  for (const m of xml.matchAll(/<row\b[^>]*\br="(\d+)"/g)) max = Math.max(max, Number(m[1]));
  return max;
}

/** Renumber a single row element (its `r` and every cell ref) from oldNum to newNum. */
function renumberRow(rowXml: string, oldNum: number, newNum: number): string {
  if (oldNum === newNum) return rowXml;
  return rowXml
    .replace(new RegExp(`(<row\\b[^>]*\\br=")${oldNum}(")`), `$1${newNum}$2`)
    .replace(new RegExp(`(\\br="[A-Z]+)${oldNum}(")`, "g"), `$1${newNum}$2`);
}

export function appendXlsxRows(bytes: Uint8Array, appends: readonly XlsxRowAppend[]): XlsxWriteResult {
  const pkg = openOfficePackage(bytes);
  const wb = openXlsxWorkbook(bytes);
  const bySheet = new Map<string, XlsxRowAppend[]>();
  for (const a of appends) {
    const list = bySheet.get(a.sheet);
    if (list) list.push(a);
    else bySheet.set(a.sheet, [a]);
  }

  let out = pkg;
  let applied = 0;
  let failed = 0;
  for (const [sheetName, sheetAppends] of bySheet) {
    const ref = wb.resolveSheet(sheetName);
    const xml = ref ? pkg.readText(ref.part) : undefined;
    const sdMatch = xml != null ? /<sheetData\b[^>]*>[\s\S]*?<\/sheetData>|<sheetData\b[^>]*\/>/.exec(xml) : null;
    if (!ref || xml == null || !sdMatch) {
      failed += sheetAppends.length;
      continue;
    }
    const grid = wb.readSheet(ref);
    const byField = columnMap(grid[sheetAppends[0]!.headerRow] ?? []);
    let nextRow = maxRowNumber(xml) + 1;
    const newRows: string[] = [];
    for (const a of sheetAppends) {
      const byCol = new Map<string, string>();
      for (const [field, value] of Object.entries(a.values)) {
        const col = byField.get(field.toLowerCase());
        if (col && value !== "") byCol.set(col, value);
      }
      newRows.push(buildRowXml(nextRow, byCol));
      nextRow++;
      applied++;
    }
    // Insert the new rows just before </sheetData> (or expand a self-closed sheetData).
    const block = sdMatch[0];
    const rebuilt = block.endsWith("/>")
      ? block.replace(/\/>$/, `>${newRows.join("")}</sheetData>`)
      : block.replace(/<\/sheetData>$/, `${newRows.join("")}</sheetData>`);
    out = out.withPart(ref.part, xml.slice(0, sdMatch.index) + rebuilt + xml.slice(sdMatch.index + block.length));
  }
  return { bytes: applied > 0 ? out.toBytes() : bytes, applied, failed };
}

function deleteRowsInSheetXml(xml: string, deleted: ReadonlySet<number>): string | null {
  const sdMatch = /<sheetData\b[^>]*>([\s\S]*?)<\/sheetData>/.exec(xml);
  if (!sdMatch) return null;
  const body = sdMatch[1] ?? "";
  const rows: { num: number; xml: string }[] = [];
  for (const m of body.matchAll(/<row\b[^>]*\br="(\d+)"[^>]*(?:\/>|>[\s\S]*?<\/row>)/g)) {
    rows.push({ num: Number(m[1]), xml: m[0] });
  }
  const sorted = [...deleted].sort((a, b) => a - b);
  const shiftFor = (n: number): number => sorted.filter((d) => d < n).length;
  const kept = rows
    .filter((r) => !deleted.has(r.num))
    .map((r) => renumberRow(r.xml, r.num, r.num - shiftFor(r.num)))
    .join("");
  const openTag = sdMatch[0].slice(0, sdMatch[0].indexOf(">") + 1);
  const rebuilt = `${openTag}${kept}</sheetData>`;
  return xml.slice(0, sdMatch.index) + rebuilt + xml.slice(sdMatch.index + sdMatch[0].length);
}

export function deleteXlsxRows(bytes: Uint8Array, deletions: readonly XlsxRowDelete[]): XlsxWriteResult {
  const pkg = openOfficePackage(bytes);
  const wb = openXlsxWorkbook(bytes);
  const bySheet = new Map<string, Set<number>>();
  for (const d of deletions) {
    const set = bySheet.get(d.sheet);
    if (set) set.add(d.row);
    else bySheet.set(d.sheet, new Set([d.row]));
  }

  let out = pkg;
  let applied = 0;
  let failed = 0;
  for (const [sheetName, rows] of bySheet) {
    const ref = wb.resolveSheet(sheetName);
    const xml = ref ? pkg.readText(ref.part) : undefined;
    const next = ref && xml != null ? deleteRowsInSheetXml(xml, rows) : null;
    if (!ref || next == null) {
      failed += rows.size;
      continue;
    }
    out = out.withPart(ref.part, next);
    applied += rows.size;
  }
  return { bytes: applied > 0 ? out.toBytes() : bytes, applied, failed };
}
