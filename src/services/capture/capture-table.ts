import { parseMarkdownTables, type ParsedTable } from "../../domain/extract/markdown-table";
import { escapeTableCell } from "../../util/markdown";

/**
 * Writing a captured row into a note's table.
 *
 * The existing add-row path locates its table through an *existing* row's provenance, which is why a view
 * with no rows yet can't be added to — there's nothing to derive a position from. Capture can't inherit that
 * limitation: the whole point is that the first item might arrive before anyone has typed a table by hand.
 *
 * So this addresses the table directly — by the heading above it, or the note's first table — and will write
 * the header itself when the table isn't there. Everything here is pure string work on file content so it
 * can be tested without a vault.
 */

export interface CaptureAppendResult {
  readonly content: string;
  readonly ok: boolean;
  readonly reason?: string;
  /** True when the table had to be written from scratch. */
  readonly createdTable: boolean;
}

function detectNewline(content: string): string {
  return content.includes("\r\n") ? "\r\n" : "\n";
}

/** Heading text at a given line, or null if that line isn't a heading. */
function headingAt(line: string): { text: string; level: number } | null {
  const m = /^(#{1,6})\s+(.*)$/.exec(line);
  if (!m) return null;
  return { text: m[2]!.trim(), level: m[1]!.length };
}

function sameHeading(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}

/**
 * Find the table that belongs to a heading: the first one after it, but stopping at the next heading of the
 * same or higher level so a table from a later section is never mistaken for this one's.
 */
function tableUnderHeading(content: string, tables: readonly ParsedTable[], heading: string): ParsedTable | null {
  const lines = content.split(/\r?\n/);
  let start = -1;
  let level = 0;
  for (let i = 0; i < lines.length; i++) {
    const h = headingAt(lines[i] ?? "");
    if (h && sameHeading(h.text, heading)) {
      start = i;
      level = h.level;
      break;
    }
  }
  if (start < 0) return null;

  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    const h = headingAt(lines[i] ?? "");
    if (h && h.level <= level) {
      end = i;
      break;
    }
  }
  return tables.find((t) => t.headerLine > start && t.headerLine < end) ?? null;
}

/** Where a new section's table should be inserted: the end of that heading's section, or the file's end. */
function sectionEnd(content: string, heading: string): number | null {
  const lines = content.split(/\r?\n/);
  let start = -1;
  let level = 0;
  for (let i = 0; i < lines.length; i++) {
    const h = headingAt(lines[i] ?? "");
    if (h && sameHeading(h.text, heading)) {
      start = i;
      level = h.level;
      break;
    }
  }
  if (start < 0) return null;
  for (let i = start + 1; i < lines.length; i++) {
    const h = headingAt(lines[i] ?? "");
    if (h && h.level <= level) return i;
  }
  return lines.length;
}

function rowLine(headers: readonly string[], values: Readonly<Record<string, string>>): string {
  const cells = headers.map((h) => escapeTableCell(values[h] ?? values[h.trim()] ?? ""));
  return `| ${cells.join(" | ")} |`;
}

export interface AppendOptions {
  /** Table under this heading. Empty/undefined = the note's first table. */
  readonly heading?: string;
  /** Write the table (and heading, if named) when it isn't there. */
  readonly createIfMissing?: boolean;
  /** Header order used only when creating a table. */
  readonly columns?: readonly string[];
}

/**
 * Append a row to the target table, creating the table when asked to and it isn't there.
 *
 * Values are keyed by column name and matched to the table's *existing* headers, so a table that has drifted
 * from the view's configuration still receives what it can hold rather than being rewritten — capture should
 * never silently restructure a file someone else's notes live in.
 */
export function appendCapturedRow(
  content: string,
  values: Readonly<Record<string, string>>,
  options: AppendOptions = {},
): CaptureAppendResult {
  const nl = detectNewline(content);
  const tables = parseMarkdownTables(content);
  const heading = options.heading?.trim() ?? "";

  const table = heading !== "" ? tableUnderHeading(content, tables, heading) : (tables[0] ?? null);

  if (table) {
    const lines = content.split(/\r?\n/);
    const lastLine = table.rows.reduce((max, row) => Math.max(max, row.line), table.separatorLine);
    lines.splice(lastLine + 1, 0, rowLine(table.headers, values));
    return { content: lines.join(nl), ok: true, createdTable: false };
  }

  if (options.createIfMissing !== true) {
    const where = heading !== "" ? `under "${heading}"` : "in that note";
    return { content, ok: false, createdTable: false, reason: `No table found ${where}.` };
  }

  const headers = (options.columns ?? []).filter((c) => c.trim() !== "");
  if (headers.length === 0) {
    return { content, ok: false, createdTable: false, reason: "Can't create a table without any columns." };
  }

  const block = [
    `| ${headers.join(" | ")} |`,
    `| ${headers.map(() => "---").join(" | ")} |`,
    rowLine(headers, values),
  ];

  const lines = content.split(/\r?\n/);
  if (heading !== "") {
    const at = sectionEnd(content, heading);
    if (at !== null) {
      // Keep a blank line between the heading (or its prose) and the new table.
      const insert = at > 0 && (lines[at - 1] ?? "").trim() !== "" ? ["", ...block] : block;
      lines.splice(at, 0, ...insert);
      return { content: lines.join(nl), ok: true, createdTable: true };
    }
    // The heading doesn't exist yet either — add it, then the table.
    const tail = lines.length > 0 && (lines[lines.length - 1] ?? "").trim() !== "" ? [""] : [];
    return {
      content: [...lines, ...tail, `## ${heading}`, "", ...block].join(nl),
      ok: true,
      createdTable: true,
    };
  }

  const tail = lines.length > 0 && (lines[lines.length - 1] ?? "").trim() !== "" ? [""] : [];
  return { content: [...lines, ...tail, ...block].join(nl), ok: true, createdTable: true };
}
