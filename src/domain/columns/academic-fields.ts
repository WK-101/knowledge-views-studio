/**
 * Resolve the kit's semantic fields (title, DOI, authors, …) to a view's columns, so DOI/OpenAlex
 * lookups keep working when a column is renamed. Type-based fields resolve from the column type (which
 * is stable across renames); name-based fields (title/year/venue/summary) fall back to name heuristics
 * and can be pinned explicitly via a per-view field map.
 */
export type AcademicField = "citekey" | "authors" | "year" | "title" | "venue" | "doi" | "tags" | "summary" | "cites";

export const ACADEMIC_FIELD_LABELS: Record<AcademicField, string> = {
  citekey: "Citation key",
  authors: "Authors",
  year: "Year",
  title: "Title",
  venue: "Venue",
  doi: "DOI",
  tags: "Tags",
  summary: "Summary / abstract",
  cites: "Cites",
};

/** Fields that share a generic type and so benefit from an explicit mapping (offered in the editor). */
export const MAPPABLE_FIELDS: readonly AcademicField[] = ["title", "year", "venue", "summary"];

const RESOLVERS: Record<AcademicField, (name: string, type: string) => boolean> = {
  citekey: (n, t) => t === "citekey" || /^cite ?key$/i.test(n),
  authors: (n, t) => t === "authors" || /^authors?$/i.test(n),
  year: (n) => /^(year|date|published)$/i.test(n),
  title: (n) => /^(title|paper)$/i.test(n),
  venue: (n) => /^(venue|journal|publication|source|conference|proceedings)$/i.test(n),
  doi: (_n, t) => t === "doi",
  tags: (_n, t) => t === "tags",
  summary: (n, t) => t === "markdown" && /^(summary|abstract)$/i.test(n),
  cites: (n, t) => t === "relation" || /^cites$/i.test(n),
};

/** Find the column for a semantic field: explicit map first, then type/name heuristics. */
export function resolveFieldColumn<C extends { name: string; type: string }>(
  cols: readonly C[],
  field: AcademicField,
  fieldMap?: Readonly<Record<string, string>>,
): C | undefined {
  const mapped = fieldMap?.[field];
  if (mapped && mapped.trim() !== "") {
    const hit = cols.find((c) => c.name.toLowerCase() === mapped.toLowerCase());
    if (hit) return hit;
  }
  return cols.find((c) => RESOLVERS[field](c.name, c.type));
}
