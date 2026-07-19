import type { ExtractedField, PageSnapshot, RawTable } from "./extract";

/**
 * Finding many rows on one page.
 *
 * This is the capability that only makes sense for a row-shaped tool. Every clipper in existence is
 * one-page-one-note, because a note is what they produce — so a journal contents page, a search result list,
 * a bibliography or a comparison table all collapse into a single blob of prose. A view made of rows can
 * take all of them at once, and that difference is worth more than any amount of polish on single capture.
 *
 * Two sources are trusted here, both because they carry their own structure rather than requiring it to be
 * guessed from layout: real HTML tables, and JSON-LD lists of entities. Repeated `<div>` patterns are
 * deliberately not inferred — that guess is wrong often enough to produce confident nonsense, and a wrong
 * row is worse than a missing one because someone has to find it later.
 */

export interface RowCandidate {
  /** Column names, in the order they appeared. */
  readonly headers: readonly string[];
  /** Each row's values, aligned to `headers`. */
  readonly rows: readonly (readonly string[])[];
  /** Where these came from, so a person can tell two tables apart before capturing. */
  readonly label: string;
  readonly kind: "table" | "list";
}

/** Below this, a "table" is almost always page furniture rather than data. */
const MIN_ROWS = 2;
/** A single column is a list, not a table, and rarely worth capturing as rows. */
const MIN_COLUMNS = 2;
/** Guard against a runaway page: nobody reviews a thousand rows in a popup. */
export const MAX_CANDIDATE_ROWS = 200;

function clean(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

/** Make headers usable as column names: named, unique, and not empty. */
export function normalizeHeaders(raw: readonly string[]): string[] {
  const out: string[] = [];
  const seen = new Map<string, number>();
  raw.forEach((header, index) => {
    let name = clean(header);
    if (name === "") name = `Column ${String(index + 1)}`;
    const count = seen.get(name.toLowerCase()) ?? 0;
    seen.set(name.toLowerCase(), count + 1);
    // A repeated header would silently overwrite the first one downstream, so make it distinct.
    out.push(count === 0 ? name : `${name} ${String(count + 1)}`);
  });
  return out;
}

/**
 * Whether a table looks like data rather than layout.
 *
 * Pages still use tables to position things, and those have the same tags as real ones. Requiring several
 * rows, more than one column, and headers that are actually filled in rejects most of the layout cases
 * without needing to understand the page.
 */
export function looksLikeData(table: RawTable): boolean {
  if (table.rows.length < MIN_ROWS) return false;
  if (table.headers.length < MIN_COLUMNS) return false;
  const named = table.headers.filter((h) => clean(h) !== "").length;
  if (named < Math.ceil(table.headers.length / 2)) return false;
  // A table whose rows are almost entirely empty is a layout scaffold.
  const filled = table.rows.filter((row) => row.some((cell) => clean(cell) !== "")).length;
  return filled >= MIN_ROWS;
}

/** Trim and pad each row so every one lines up with the headers. */
function alignRows(headers: readonly string[], rows: readonly (readonly string[])[]): string[][] {
  return rows
    .map((row) => headers.map((_, i) => clean(row[i] ?? "")))
    .filter((row) => row.some((cell) => cell !== ""))
    .slice(0, MAX_CANDIDATE_ROWS);
}

/** Row candidates from the page's HTML tables. */
export function tableCandidates(page: PageSnapshot): RowCandidate[] {
  const out: RowCandidate[] = [];
  (page.tables ?? []).forEach((table, index) => {
    if (!looksLikeData(table)) return;
    const headers = normalizeHeaders(table.headers);
    const rows = alignRows(headers, table.rows);
    if (rows.length < MIN_ROWS) return;
    const caption = clean(table.caption ?? "");
    out.push({
      headers,
      rows,
      label: caption !== "" ? caption : `Table ${String(index + 1)}`,
      kind: "table",
    });
  });
  return out;
}

/** Properties worth lifting out of a list entity, in the order they should appear as columns. */
const LIST_FIELDS: readonly { key: string; header: string }[] = [
  { key: "name", header: "Title" },
  { key: "headline", header: "Title" },
  { key: "author", header: "Author" },
  { key: "creator", header: "Author" },
  { key: "datePublished", header: "Published" },
  { key: "url", header: "URL" },
  { key: "description", header: "Description" },
];

function flatten(raw: unknown): string {
  if (typeof raw === "string") return clean(raw);
  if (typeof raw === "number" || typeof raw === "boolean") return String(raw);
  if (Array.isArray(raw)) return raw.map(flatten).filter((v) => v !== "").join(", ");
  if (raw !== null && typeof raw === "object") {
    const name = (raw as Record<string, unknown>)["name"];
    if (typeof name === "string") return clean(name);
  }
  return "";
}

/** Pull entities out of a JSON-LD block, following `ItemList` and `itemListElement` wrappers. */
function listEntities(block: unknown, depth = 0): Record<string, unknown>[] {
  if (depth > 4 || block === null || typeof block !== "object") return [];
  if (Array.isArray(block)) return block.flatMap((b) => listEntities(b, depth + 1));
  const record = block as Record<string, unknown>;
  for (const wrapper of ["itemListElement", "@graph", "hasPart"]) {
    const inner = record[wrapper];
    if (inner !== undefined) return listEntities(inner, depth + 1);
  }
  // A ListItem wraps the thing it's about.
  const item = record["item"];
  if (item !== undefined && typeof item === "object") return listEntities(item, depth + 1);
  return [record];
}

/**
 * Row candidates from JSON-LD lists — a search results page, a journal's contents, a reading list.
 *
 * More reliable than any table, because the page is describing its own entities rather than laying them out.
 */
export function listCandidates(page: PageSnapshot): RowCandidate[] {
  const entities: Record<string, unknown>[] = [];
  for (const block of page.jsonLd ?? []) entities.push(...listEntities(block));
  if (entities.length < MIN_ROWS) return [];

  // Only keep the columns at least one entity actually fills.
  const headers: string[] = [];
  const keys: string[] = [];
  for (const { key, header } of LIST_FIELDS) {
    if (headers.includes(header)) continue;
    if (entities.some((e) => flatten(e[key]) !== "")) {
      headers.push(header);
      keys.push(key);
    }
  }
  if (headers.length < MIN_COLUMNS) return [];

  const rows = entities
    .map((entity) => keys.map((key) => flatten(entity[key])))
    .filter((row) => row.some((cell) => cell !== ""))
    .slice(0, MAX_CANDIDATE_ROWS);
  if (rows.length < MIN_ROWS) return [];

  return [{ headers, rows, label: `${String(rows.length)} items on this page`, kind: "list" }];
}

/** Everything on the page that could be captured as several rows, best-structured first. */
export function findRowCandidates(page: PageSnapshot): RowCandidate[] {
  // Lists come first: a page that describes its own entities is more trustworthy than one that tabulates.
  return [...listCandidates(page), ...tableCandidates(page)];
}

/** Turn one candidate row into the fields the capture pipeline expects. */
export function candidateRowToFields(
  candidate: RowCandidate,
  rowIndex: number,
): ExtractedField[] {
  const row = candidate.rows[rowIndex];
  if (row === undefined) return [];
  const fields: ExtractedField[] = [];
  candidate.headers.forEach((header, i) => {
    const value = row[i] ?? "";
    if (value !== "") fields.push({ key: header, value });
  });
  return fields;
}
