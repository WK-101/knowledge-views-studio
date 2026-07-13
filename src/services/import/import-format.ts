import { strFromU8, unzipSync } from "fflate";
import type { ExportTable } from "../export/export-format";

/** Ensure every row has exactly headers.length cells and no header is blank. */
export function normalizeTable(table: ExportTable): ExportTable {
  let headers = [...table.headers];
  if (headers.length === 0) {
    const width = table.rows.reduce((m, r) => Math.max(m, r.length), 0);
    headers = Array.from({ length: width }, (_, i) => `Column ${i + 1}`);
  }
  headers = headers.map((h, i) => (h.trim() === "" ? `Column ${i + 1}` : h));
  const width = headers.length;
  const rows = table.rows.map((row) => {
    const r = row.slice(0, width);
    while (r.length < width) r.push("");
    return r;
  });
  return { headers, rows };
}

// ---- CSV (RFC 4180 state machine: honours quotes, escaped quotes, embedded newlines) ----
export function parseCsv(text: string): ExportTable {
  const records: string[][] = [];
  let field = "";
  let record: string[] = [];
  let inQuotes = false;
  let i = 0;
  const n = text.length;
  const endField = (): void => {
    record.push(field);
    field = "";
  };
  const endRecord = (): void => {
    endField();
    records.push(record);
    record = [];
  };
  while (i < n) {
    const c = text[i]!;
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i += 2;
          continue;
        }
        inQuotes = false;
        i += 1;
        continue;
      }
      field += c;
      i += 1;
      continue;
    }
    if (c === '"') {
      inQuotes = true;
      i += 1;
    } else if (c === ",") {
      endField();
      i += 1;
    } else if (c === "\n") {
      endRecord();
      i += 1;
    } else if (c === "\r") {
      endRecord();
      i += text[i + 1] === "\n" ? 2 : 1;
    } else {
      field += c;
      i += 1;
    }
  }
  if (field !== "" || record.length > 0) endRecord();
  const nonEmpty = records.filter((r) => r.some((cell) => cell !== ""));
  return { headers: nonEmpty[0] ?? [], rows: nonEmpty.slice(1) };
}

// ---- Markdown pipe table (parses the first table found) ----
function isSeparatorRow(line: string): boolean {
  return /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/.test(line);
}

function isTableRow(line: string): boolean {
  return line.includes("|") && line.trim() !== "";
}

function splitRow(line: string): string[] {
  let s = line.trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|")) s = s.slice(0, -1);
  const cells: string[] = [];
  let cur = "";
  for (let i = 0; i < s.length; i++) {
    const c = s[i]!;
    if (c === "\\" && s[i + 1] === "|") {
      cur += "|";
      i += 1;
      continue;
    }
    if (c === "|") {
      cells.push(cur.trim());
      cur = "";
      continue;
    }
    cur += c;
  }
  cells.push(cur.trim());
  return cells;
}

export function parseMarkdownTable(text: string): ExportTable {
  const lines = text.split(/\r?\n/);
  let start = -1;
  for (let i = 0; i < lines.length - 1; i++) {
    if (isTableRow(lines[i]!) && isSeparatorRow(lines[i + 1]!)) {
      start = i;
      break;
    }
  }
  if (start < 0) return { headers: [], rows: [] };
  const headers = splitRow(lines[start]!);
  const rows: string[][] = [];
  for (let i = start + 2; i < lines.length; i++) {
    if (!isTableRow(lines[i]!)) break;
    rows.push(splitRow(lines[i]!));
  }
  return { headers, rows };
}

// ---- XLSX (regex-based OOXML reader; works in node + browser, no DOM) ----
function xmlUnescape(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, d: string) => String.fromCharCode(Number(d)))
    .replace(/&amp;/g, "&");
}

function colToIndex(letters: string): number {
  let n = 0;
  for (const ch of letters) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}

function parseSharedStrings(xml: string | null): string[] {
  if (!xml) return [];
  const out: string[] = [];
  const siRe = /<si>([\s\S]*?)<\/si>/g;
  let m: RegExpExecArray | null;
  while ((m = siRe.exec(xml))) {
    const tRe = /<t[^>]*>([\s\S]*?)<\/t>/g;
    let t: RegExpExecArray | null;
    let s = "";
    while ((t = tRe.exec(m[1]!))) s += xmlUnescape(t[1]!);
    out.push(s);
  }
  return out;
}

function cellValue(type: string, body: string, shared: readonly string[]): string {
  const v = /<v>([\s\S]*?)<\/v>/.exec(body)?.[1];
  if (type === "s") return shared[Number(v ?? "")] ?? "";
  if (type === "inlineStr") return xmlUnescape(/<t[^>]*>([\s\S]*?)<\/t>/.exec(body)?.[1] ?? "");
  if (type === "b") return v === "1" ? "TRUE" : "FALSE";
  return xmlUnescape(v ?? "");
}

function parseSheet(xml: string, shared: readonly string[]): string[][] {
  const rows: string[][] = [];
  const rowRe = /<row\b[^>]*>([\s\S]*?)<\/row>/g;
  let rm: RegExpExecArray | null;
  while ((rm = rowRe.exec(xml))) {
    const cells: string[] = [];
    const cellRe = /<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g;
    let cm: RegExpExecArray | null;
    while ((cm = cellRe.exec(rm[1]!))) {
      const attrs = cm[1] ?? "";
      const ref = /r="([A-Z]+)\d+"/.exec(attrs)?.[1];
      const type = /t="([^"]+)"/.exec(attrs)?.[1] ?? "n";
      const colIdx = ref ? colToIndex(ref) : cells.length;
      while (cells.length < colIdx) cells.push("");
      cells[colIdx] = cellValue(type, cm[2] ?? "", shared);
    }
    rows.push(cells);
  }
  return rows;
}

export function parseXlsx(bytes: Uint8Array): ExportTable {
  const files = unzipSync(bytes);
  const read = (name: string): string | null => (files[name] ? strFromU8(files[name]) : null);
  const shared = parseSharedStrings(read("xl/sharedStrings.xml"));
  const sheetName =
    Object.keys(files)
      .filter((k) => /^xl\/worksheets\/sheet\d+\.xml$/.test(k))
      .sort()[0] ?? Object.keys(files).find((k) => /^xl\/worksheets\/.*\.xml$/.test(k));
  const sheetXml = sheetName ? strFromU8(files[sheetName]!) : null;
  if (!sheetXml) return { headers: [], rows: [] };
  const matrix = parseSheet(sheetXml, shared);
  return { headers: matrix[0] ?? [], rows: matrix.slice(1) };
}

export type ImportFormat = "csv" | "markdown" | "xlsx";

export function detectImportFormat(fileName: string): ImportFormat {
  const lower = fileName.toLowerCase();
  if (lower.endsWith(".xlsx")) return "xlsx";
  if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
  return "csv";
}
