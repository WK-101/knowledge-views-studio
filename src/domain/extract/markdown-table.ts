import { isTableSeparator, splitTableRow } from "../../util/markdown";

export interface ParsedTableRow {
  readonly cells: string[];
  /** 0-based absolute line index in the file (enables surgical write-back). */
  readonly line: number;
}

export interface ParsedTable {
  readonly headers: string[];
  readonly headerLine: number;
  readonly separatorLine: number;
  readonly rows: ParsedTableRow[];
}

/**
 * Extract every GFM table from a note. A table is a pipe-bearing header line
 * immediately followed by a separator row, then contiguous body rows. Line
 * numbers are retained throughout so a row can be located again for write-back.
 *
 * This is the *single* table parser. The legacy codebase had two subtly
 * different copies (parser.ts and collector.ts), one of which was dead.
 */
/**
 * Lines that sit inside a fenced code block (``` or ~~~), including the fence lines themselves.
 *
 * A fenced block may legitimately *contain* a Markdown table — a tutorial note, a changelog, a README
 * kept in the vault, any note that shows what a table looks like. Those lines are documentation, not
 * data. Scraping them would put example rows into a dashboard, and editing such a row would rewrite the
 * code block in the note. So they are skipped outright.
 *
 * Indented (4-space) code blocks are deliberately *not* treated this way: tables nested inside list
 * items are legitimately indented, and excluding them would break far more than it fixed.
 */
function fencedLines(lines: readonly string[]): boolean[] {
  const inside = new Array<boolean>(lines.length).fill(false);
  const opener = /^ {0,3}(`{3,}|~{3,})/;
  let fenceChar = "";
  let fenceLen = 0;

  const closesFence = (line: string): boolean => {
    const trimmed = line.trim();
    if (trimmed.length < fenceLen) return false;
    for (const ch of trimmed) if (ch !== fenceChar) return false;
    return true;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i] ?? "";
    if (fenceLen === 0) {
      const match = opener.exec(line);
      if (match) {
        fenceChar = match[1]!.charAt(0);
        fenceLen = match[1]!.length;
        inside[i] = true;
      }
      continue;
    }
    inside[i] = true;
    if (closesFence(line)) fenceLen = 0;
  }
  return inside;
}

export function parseMarkdownTables(content: string): ParsedTable[] {
  const lines = String(content ?? "").split(/\r?\n/);
  const fenced = fencedLines(lines);
  const tables: ParsedTable[] = [];

  let i = 0;
  while (i < lines.length - 1) {
    const header = lines[i] ?? "";
    const separator = lines[i + 1] ?? "";

    if (fenced[i] || !header.includes("|") || !isTableSeparator(separator)) {
      i++;
      continue;
    }

    const headers = splitTableRow(header);
    const headerLine = i;
    const separatorLine = i + 1;
    const rows: ParsedTableRow[] = [];

    let j = i + 2;
    while (j < lines.length) {
      const line = lines[j] ?? "";
      if (fenced[j] || line.trim() === "" || !line.includes("|") || isTableSeparator(line)) break;
      rows.push({ cells: splitTableRow(line), line: j });
      j++;
    }

    if (headers.length > 0 && rows.length > 0) {
      tables.push({ headers, headerLine, separatorLine, rows });
    }

    i = Math.max(j, i + 1);
  }

  return tables;
}
