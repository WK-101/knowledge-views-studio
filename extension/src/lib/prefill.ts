import type { SchemaView } from "../../../shared/protocol";

/**
 * Filling a view's form from a page, by meaning rather than by spelling.
 *
 * The old matching was exact-name-only: a view whose column is called `Link` got nothing from the page's
 * `url` field, `Summary` got nothing from `description`, and the form sat empty next to a page full of
 * exactly the right data — which reads as "capture doesn't work", because from the outside it doesn't.
 *
 * Columns and page fields both get a vocabulary. A column matches a field when either side's name appears
 * in the other's alias set. Exact name matches still win first, so a view that genuinely has a
 * `description` column takes the page's `description` before any alias gets a say.
 */

/** For each canonical page field, the column names that mean the same thing. */
const ALIASES: Record<string, readonly string[]> = {
  url: ["url", "link", "source", "address", "source url", "web", "website", "href"],
  title: ["title", "name", "page", "heading", "site"],
  description: ["description", "summary", "excerpt", "abstract", "about", "snippet", "overview"],
  author: ["author", "authors", "by", "creator", "writer"],
  published: ["published", "published date", "publication date", "pubdate", "year", "date published"],
  created: ["created", "created date", "date added", "captured", "capture date", "added", "saved"],
  tags: ["tags", "keywords", "topics", "labels", "categories"],
  selection: ["selection", "quote", "quotes", "highlight", "highlights", "annotations", "notes"],
  doi: ["doi", "identifier", "digital object identifier"],
  publisher: ["publisher", "journal", "venue", "source name", "site name"],
};

/** The canonical field a column name answers to, or null. */
export function canonicalFor(columnName: string): string | null {
  const name = columnName.trim().toLowerCase();
  for (const [canonical, aliases] of Object.entries(ALIASES)) {
    if (aliases.includes(name)) return canonical;
  }
  return null;
}

/**
 * Values for a view's columns, from a page's extracted fields.
 *
 * Two passes. Exact names first — those are claims the view has made explicitly. Aliases second, and only
 * into columns still empty, each page field spent at most once so `url` doesn't fill both a `Link` and a
 * `Source` column with the same thing twice over.
 */
export function prefillFor(
  view: Pick<SchemaView, "columns">,
  fields: readonly { key: string; value: string }[],
): Record<string, string> {
  const out: Record<string, string> = {};
  const byKey = new Map<string, string>();
  for (const field of fields) {
    const key = field.key.trim().toLowerCase();
    if (!byKey.has(key) && field.value.trim() !== "") byKey.set(key, field.value);
  }

  // Pass one: exact.
  const spent = new Set<string>();
  for (const column of view.columns) {
    const key = column.name.trim().toLowerCase();
    const direct = byKey.get(key);
    if (direct !== undefined) {
      out[column.name] = direct;
      spent.add(canonicalFor(key) ?? key);
    }
  }

  // Pass two: meaning.
  for (const column of view.columns) {
    if (out[column.name] !== undefined) continue;
    const canonical = canonicalFor(column.name);
    if (canonical === null || spent.has(canonical)) continue;
    const value = byKey.get(canonical);
    if (value === undefined) continue;
    out[column.name] = value;
    spent.add(canonical);
  }
  return out;
}
