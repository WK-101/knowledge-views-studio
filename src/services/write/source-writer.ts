import {
  fingerprintCells,
  parseMarkdownTables,
  type ParsedTable,
  type ParsedTableRow,
  type RowProvenance,
} from "../../domain/index";
import { escapeTableCell } from "../../util/markdown";

export interface CellEdit {
  readonly provenance: RowProvenance;
  readonly column: string;
  readonly value: string;
}

export interface WriteFailure {
  readonly provenance: RowProvenance;
  readonly column?: string;
  readonly reason: string;
}

export interface CellWriteResult {
  readonly content: string;
  readonly applied: number;
  readonly failures: WriteFailure[];
}

export interface RowWriteResult {
  readonly content: string;
  readonly ok: boolean;
  readonly reason?: string;
}

const detectNewline = (content: string): string => (content.includes("\r\n") ? "\r\n" : "\n");

interface Located {
  readonly table: ParsedTable;
  readonly row: ParsedTableRow;
}

/**
 * Find the source row. Trust the recorded (tableIndex, rowIndex) only if its
 * current fingerprint still matches; otherwise relocate by scanning for the
 * fingerprint, and refuse to act if that is missing or ambiguous. This is what
 * makes write-back safe when the note has been edited since extraction.
 */
function locateRow(tables: readonly ParsedTable[], provenance: RowProvenance): Located | null {
  const tableIndex = Number(provenance.locator.tableIndex);
  const rowIndex = Number(provenance.locator.rowIndex);
  const direct = tables[tableIndex]?.rows[rowIndex];
  if (direct && fingerprintCells(direct.cells) === provenance.fingerprint) {
    const table = tables[tableIndex];
    if (table) return { table, row: direct };
  }

  let match: Located | null = null;
  let count = 0;
  for (const table of tables) {
    for (const row of table.rows) {
      if (fingerprintCells(row.cells) === provenance.fingerprint) {
        match = { table, row };
        count++;
      }
    }
  }
  return count === 1 ? match : null;
}

/** Top-level pipe positions, ignoring pipes inside code, wikilinks, links, or escapes. */
function topLevelPipes(line: string): number[] {
  const pipes: number[] = [];
  let inCode = false;
  let wiki = 0;
  let bracket = 0;
  let paren = 0;
  let escaped = false;

  for (let i = 0; i < line.length; i++) {
    const ch = line.charAt(i);
    const next = line.charAt(i + 1);
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === "\\") {
      escaped = true;
      continue;
    }
    if (ch === "`") {
      inCode = !inCode;
      continue;
    }
    if (!inCode) {
      if (ch === "[" && next === "[") {
        wiki++;
        i++;
        continue;
      }
      if (ch === "]" && next === "]" && wiki > 0) {
        wiki--;
        i++;
        continue;
      }
      if (wiki === 0) {
        if (ch === "[") bracket++;
        else if (ch === "]" && bracket > 0) bracket--;
        else if (ch === "(") paren++;
        else if (ch === ")" && paren > 0) paren--;
      }
    }
    if (ch === "|" && !inCode && wiki === 0 && bracket === 0 && paren === 0) pipes.push(i);
  }
  return pipes;
}

interface RowSegments {
  readonly segments: string[];
  readonly cellStart: number;
  readonly cellCount: number;
}

/** Split a row into raw (untrimmed) segments so only the target cell is rewritten. */
function splitSegments(line: string): RowSegments {
  const pipes = topLevelPipes(line);
  const segments: string[] = [];
  let start = 0;
  for (const pipe of pipes) {
    segments.push(line.slice(start, pipe));
    start = pipe + 1;
  }
  segments.push(line.slice(start));

  const trimmed = line.trim();
  const leading = trimmed.startsWith("|");
  const trailing = trimmed.endsWith("|") && trimmed.length > 1;
  const cellStart = leading ? 1 : 0;
  const cellCount = segments.length - cellStart - (trailing ? 1 : 0);
  return { segments, cellStart, cellCount };
}

/** Replace a cell segment's content, preserving its surrounding whitespace. */
function replaceCellSegment(segment: string, newRaw: string): string {
  const m = segment.match(/^(\s*)([\s\S]*?)(\s*)$/);
  if (!m) return ` ${newRaw} `;
  const lead = m[1] ?? "";
  const body = m[2] ?? "";
  const trail = m[3] ?? "";
  if (body === "") return ` ${newRaw} `;
  return `${lead}${newRaw}${trail}`;
}

function replaceCellsInLine(line: string, replacements: ReadonlyMap<number, string>, targetWidth: number): string {
  const { segments, cellStart, cellCount } = splitSegments(line);
  // Pad short rows: a row with fewer cells than the header can't receive an edit to a trailing column
  // (e.g. a last "Note" column) until the missing cells exist. Add empty cells up to the needed width.
  let maxIdx = cellCount - 1;
  for (const k of replacements.keys()) if (k > maxIdx) maxIdx = k;
  const needed = Math.max(targetWidth, maxIdx + 1);
  let effectiveCount = cellCount;
  if (needed > cellCount) {
    const pad: string[] = [];
    for (let i = cellCount; i < needed; i++) pad.push("  ");
    segments.splice(cellStart + cellCount, 0, ...pad); // insert before the trailing segment
    effectiveCount = needed;
  }
  for (const [colIndex, newRaw] of replacements) {
    if (colIndex < 0 || colIndex >= effectiveCount) continue;
    const segIdx = cellStart + colIndex;
    segments[segIdx] = replaceCellSegment(segments[segIdx] ?? "", newRaw);
  }
  return segments.join("|");
}

function columnIndex(table: ParsedTable, column: string): number {
  const key = column.trim().toLowerCase();
  return table.headers.findIndex((h) => h.trim().toLowerCase() === key);
}

/**
 * Apply a batch of cell edits to one file's content. Every edit is located
 * against the *original* content first, then grouped by line and applied, so
 * multiple edits to the same row (or different rows) compose correctly.
 */
export function applyCellEdits(content: string, edits: readonly CellEdit[]): CellWriteResult {
  const nl = detectNewline(content);
  const lines = content.split(/\r?\n/);
  const tables = parseMarkdownTables(content);
  const failures: WriteFailure[] = [];
  const perLine = new Map<number, Map<number, string>>();
  const perLineWidth = new Map<number, number>();

  for (const edit of edits) {
    const located = locateRow(tables, edit.provenance);
    if (!located) {
      failures.push({ provenance: edit.provenance, column: edit.column, reason: "Source row not found (it may have changed)." });
      continue;
    }
    const colIndex = columnIndex(located.table, edit.column);
    if (colIndex < 0) {
      failures.push({ provenance: edit.provenance, column: edit.column, reason: `Column "${edit.column}" is not in the source table.` });
      continue;
    }
    // A short row (fewer cells than headers) is padded at write time, so trailing columns are writable.
    let map = perLine.get(located.row.line);
    if (!map) {
      map = new Map();
      perLine.set(located.row.line, map);
    }
    map.set(colIndex, escapeTableCell(edit.value));
    perLineWidth.set(located.row.line, located.table.headers.length);
  }

  let applied = 0;
  for (const [lineIndex, replacements] of perLine) {
    const original = lines[lineIndex];
    if (original === undefined) continue;
    lines[lineIndex] = replaceCellsInLine(original, replacements, perLineWidth.get(lineIndex) ?? 0);
    applied += replacements.size;
  }

  return { content: lines.join(nl), applied, failures };
}

/** Delete one or more rows (located by fingerprint); all-or-nothing per call. */
export function deleteRows(content: string, provenances: readonly RowProvenance[]): RowWriteResult {
  const nl = detectNewline(content);
  const lines = content.split(/\r?\n/);
  const tables = parseMarkdownTables(content);
  const lineSet = new Set<number>();

  for (const provenance of provenances) {
    const located = locateRow(tables, provenance);
    if (!located) return { content, ok: false, reason: "Source row not found (it may have changed)." };
    lineSet.add(located.row.line);
  }

  for (const index of [...lineSet].sort((a, b) => b - a)) lines.splice(index, 1);
  return { content: lines.join(nl), ok: true };
}

/** Append a new row to the table that contains the reference row. */
export function appendRow(
  content: string,
  reference: RowProvenance,
  values: Readonly<Record<string, string>>,
): RowWriteResult {
  const nl = detectNewline(content);
  const lines = content.split(/\r?\n/);
  const tables = parseMarkdownTables(content);
  const located = locateRow(tables, reference);
  if (!located) return { content, ok: false, reason: "Source table not found (it may have changed)." };

  const { table } = located;
  const lastLine = table.rows.reduce((max, row) => Math.max(max, row.line), table.separatorLine);
  const cells = table.headers.map((h) => escapeTableCell(values[h] ?? values[h.trim()] ?? ""));
  lines.splice(lastLine + 1, 0, `| ${cells.join(" | ")} |`);
  return { content: lines.join(nl), ok: true };
}
