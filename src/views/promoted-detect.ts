/** Pure helpers for the "has a dedicated note" gutter indicator (testable, no Obsidian deps). */

/** Name of the column holding a row's dedicated-note link: a column named "Note" (any type), else a
 *  "link"-typed column. Returns the column NAME or null. */
export function noteLinkColumnName(columns: readonly { name: string; type: string }[]): string | null {
  const byName = columns.find((c) => /^note$/i.test(c.name.trim()));
  if (byName) return byName.name;
  const byType = columns.find((c) => c.type === "link");
  return byType ? byType.name : null;
}

/** The wikilink target inside a value like `[[Note]]` or `[[Note|alias]]`, or null. */
export function wikilinkTarget(value: string): string | null {
  const m = /\[\[([^\]|]+)/.exec(value ?? "");
  return m ? (m[1] ?? "").trim() || null : null;
}

/** Name of the citation-key column (type "citekey", else a column named "Cite key"), or null. */
export function citeKeyColumnName(columns: readonly { name: string; type: string }[]): string | null {
  const byType = columns.find((c) => c.type === "citekey");
  if (byType) return byType.name;
  const byName = columns.find((c) => /^cite ?key$/i.test(c.name.trim()));
  return byName ? byName.name : null;
}
