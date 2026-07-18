import { normalizeForType, normalizeText } from "./normalize";
import type { CaptureColumn, CaptureField, CapturePayload, MappedCapture } from "./types";

/**
 * Matching captured fields to a view's columns.
 *
 * This is the part of capture that other tools can't have. Web Clipper asks you to write a template, Modal
 * Forms asks you to write JSON, QuickAdd asks you to write a format string — all because none of them knows
 * what your data looks like. A view here already declares its columns, their types, their roles, and the
 * vocabulary each column already contains, so the mapping can simply be worked out.
 *
 * Nothing is discarded. Fields that match no column are handed back as `unmapped` so the review step can
 * offer them, rather than being silently dropped the way a template that forgot a variable would.
 */

/**
 * Names the same idea travels under. Sources are wildly inconsistent — a title arrives as `og:title`,
 * `schema:name`, `dc.title` or `headline` depending on who built the page — so aliases are pooled by
 * meaning and matched after exact column names have had their chance.
 */
const ALIASES: Readonly<Record<string, readonly string[]>> = {
  title: ["title", "name", "headline", "og:title", "twitter:title", "schema:name", "schema:headline", "dc.title", "citation_title"],
  url: ["url", "link", "permalink", "canonical", "og:url", "schema:url", "source"],
  author: ["author", "authors", "creator", "byline", "og:article:author", "schema:author", "dc.creator", "citation_author"],
  date: ["date", "published", "pubdate", "publisheddate", "datepublished", "og:article:published_time", "schema:datepublished", "dc.date", "citation_publication_date"],
  description: ["description", "summary", "abstract", "excerpt", "og:description", "twitter:description", "schema:description", "dc.description"],
  tags: ["tags", "keywords", "categories", "category", "schema:keywords", "dc.subject"],
  publisher: ["publisher", "site", "sitename", "og:site_name", "schema:publisher", "journal", "container-title"],
  doi: ["doi", "schema:doi", "citation_doi"],
  image: ["image", "thumbnail", "og:image", "twitter:image", "schema:image"],
  isbn: ["isbn", "schema:isbn"],
  rating: ["rating", "score", "schema:ratingvalue"],
  price: ["price", "schema:price", "amount", "cost"],
};

/** Column-name hints per concept, used when a column carries no explicit role. */
const COLUMN_HINTS: Readonly<Record<string, readonly string[]>> = {
  title: ["title", "name", "paper", "article", "book", "film", "movie"],
  url: ["url", "link", "source", "website"],
  author: ["author", "authors", "creator", "by", "artist", "director"],
  date: ["date", "published", "year", "released", "added"],
  description: ["description", "summary", "abstract", "notes", "note"],
  tags: ["tags", "keywords", "topics", "labels"],
  publisher: ["publisher", "journal", "site", "studio", "label"],
  doi: ["doi"],
  image: ["image", "cover", "thumbnail", "poster"],
  isbn: ["isbn"],
  rating: ["rating", "score", "stars"],
  price: ["price", "cost", "amount"],
};

/** Column types that imply a concept even when the name gives nothing away. */
const TYPE_CONCEPT: Readonly<Record<string, string>> = {
  url: "url",
  image: "image",
  doi: "doi",
  authors: "author",
  tags: "tags",
};

function key(raw: string): string {
  return normalizeText(raw).toLowerCase().replace(/[\s_-]+/g, "");
}

/** Which concept, if any, a captured field's key represents. */
function conceptOfField(fieldKey: string): string | null {
  const k = key(fieldKey);
  for (const [concept, names] of Object.entries(ALIASES)) {
    if (names.some((n) => key(n) === k)) return concept;
  }
  return null;
}

/** Which concept a column is asking for, from its role, then its type, then its name. */
function conceptOfColumn(column: CaptureColumn): string | null {
  if (column.role === "title") return "title";
  if (column.role === "date") return "date";
  if (column.role === "tags") return "tags";
  const byType = TYPE_CONCEPT[column.typeId];
  if (byType) return byType;
  const k = key(column.name);
  for (const [concept, hints] of Object.entries(COLUMN_HINTS)) {
    if (hints.some((h) => key(h) === k)) return concept;
  }
  return null;
}

/**
 * Snap a value onto a choice column's existing vocabulary, case- and spacing-insensitively.
 *
 * Capturing "In Progress" into a column whose options say "In progress" should not invent a second spelling
 * of the same status — that's how a tidy column quietly becomes an untidy one.
 */
function snapToOption(value: string, column: CaptureColumn): string {
  const options = column.options;
  if (!options || options.length === 0) return value;
  const k = key(value);
  const existing = options.find((option) => key(option.value) === k);
  return existing ? existing.value : value;
}

/**
 * Match a payload's fields to a view's columns.
 *
 * Precedence runs from most to least certain: an exact column-name match wins, then a shared concept
 * (via alias and role/type/name hints), and finally the payload's own url fills a url-ish column that
 * nothing else claimed. A column already filled is never overwritten by a weaker match.
 */
export function mapToColumns(payload: CapturePayload, columns: readonly CaptureColumn[]): MappedCapture {
  const values: Record<string, string> = {};
  const claimed = new Set<string>();

  const put = (column: CaptureColumn, raw: string): void => {
    if (values[column.name] !== undefined) return;
    const normalized = normalizeForType(raw, column.typeId);
    if (normalized === "") return;
    values[column.name] = column.typeId === "select" ? snapToOption(normalized, column) : normalized;
  };

  // 1. Exact column-name matches — the source used the same word the column does.
  for (const field of payload.fields) {
    const match = columns.find((c) => key(c.name) === key(field.key));
    if (match) {
      put(match, field.value);
      claimed.add(field.key);
    }
  }

  // 2. Shared concept: the field and the column mean the same thing under different names.
  for (const field of payload.fields) {
    if (claimed.has(field.key)) continue;
    const concept = conceptOfField(field.key);
    if (concept === null) continue;
    const match = columns.find((c) => conceptOfColumn(c) === concept && values[c.name] === undefined);
    if (match) {
      put(match, field.value);
      claimed.add(field.key);
    }
  }

  // 3. The source URL fills a url column when nothing else did — provenance is worth keeping by default.
  if (payload.url !== undefined && payload.url.trim() !== "") {
    const urlColumn = columns.find((c) => conceptOfColumn(c) === "url" && values[c.name] === undefined);
    if (urlColumn) put(urlColumn, payload.url);
  }

  const unmapped: CaptureField[] = payload.fields.filter((f) => !claimed.has(f.key) && normalizeText(f.value) !== "");
  return { values, unmapped };
}

/** Fill any still-empty column that declares a default. Mirrors what adding a row by hand would do. */
export function applyDefaults(
  values: Readonly<Record<string, string>>,
  columns: readonly CaptureColumn[],
): Record<string, string> {
  const out: Record<string, string> = { ...values };
  for (const column of columns) {
    const existing = out[column.name];
    if ((existing === undefined || existing === "") && column.defaultValue) {
      out[column.name] = column.defaultValue;
    }
  }
  return out;
}
