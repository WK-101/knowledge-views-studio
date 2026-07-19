import { safeName } from "../../../shared/template";
import { noteLinkColumnName } from "../../views/promoted-detect";

/**
 * Planning a row's dedicated note, for any view.
 *
 * Promotion existed only for papers: the fields were title/authors/venue/doi, the folder was `Papers`, and
 * the identity was the DOI. A reading list, a recipe collection, or a product-research view had rows that
 * could never become notes — although the row-note link, the frontmatter matching and the gutter indicator
 * would all have worked for them unchanged.
 *
 * This module is the general case, kept pure so it can be tested without a vault. Everything the row knows
 * becomes a template variable — the cells are the vocabulary, because in a general view the cells are all
 * there is. The academic path keeps its own richer flow (Zotero enrichment, citation keys); this is for
 * everything that isn't a paper.
 */

export interface PromotionInput {
  /** The row's cells, as the view resolves them. */
  readonly cells: Readonly<Record<string, string>>;
  readonly columns: readonly { readonly name: string; readonly type?: string }[];
  /** The frontmatter key that identifies this row's note (from `dedicatedNoteKeyFor`). */
  readonly matchKey: string;
  /** Per-view folder setting, if any. */
  readonly configuredFolder?: string;
  /** The view's first scope folder, for the default location. */
  readonly scopeFolder?: string;
}

export interface PromotionPlan {
  /** The row's identity value — what the note's frontmatter must carry to be *this* row's note. */
  readonly matchValue: string;
  readonly folder: string;
  /** Filename without extension, already path-safe. */
  readonly fileBase: string;
  /** Every variable the note template may refer to. */
  readonly variables: Readonly<Record<string, string>>;
  /** The row column that should hold the wikilink back, or null when the view has none. */
  readonly noteLinkColumn: string | null;
}

/** The cell that identifies the row for a given key, tolerating the aliases URLs actually live under. */
export function identityCell(
  cells: Readonly<Record<string, string>>,
  matchKey: string,
): string {
  const key = matchKey.trim().toLowerCase();
  if (key === "") return "";
  const names = Object.keys(cells);
  const exact = names.find((n) => n.toLowerCase() === key);
  if (exact !== undefined && cells[exact]?.trim() !== "") return cells[exact]?.trim() ?? "";
  // `source` is the frontmatter convention; the column is almost always called URL or Link.
  if (key === "source") {
    for (const alias of ["url", "link", "source url", "address"]) {
      const found = names.find((n) => n.toLowerCase() === alias);
      const value = found !== undefined ? (cells[found]?.trim() ?? "") : "";
      if (value !== "") return value;
    }
  }
  return "";
}

/** The cell a note should be named after: whatever identifies the row to a person. */
function titleCell(cells: Readonly<Record<string, string>>): string {
  const names = Object.keys(cells);
  for (const wanted of ["title", "name", "page", "site"]) {
    const found = names.find((n) => n.toLowerCase() === wanted);
    const value = found !== undefined ? (cells[found]?.trim() ?? "") : "";
    if (value !== "") return value;
  }
  // First non-empty short cell, rather than an unnamed file.
  for (const name of names) {
    const value = cells[name]?.trim() ?? "";
    if (value !== "" && value.length <= 120 && !value.includes("\n")) return value;
  }
  return "";
}

/** A default note template for non-academic rows: the row's own data, then room to write. */
export const DEFAULT_WEB_PROMOTED_TEMPLATE = [
  "---",
  "title: {{title|yaml}}",
  "source: {{source}}",
  "captured: {{date}}",
  "---",
  "",
  "# {{title}}",
  "",
  "## Annotations",
  "",
  "{{annotations}}",
  "",
  "## Notes",
  "",
  "",
].join("\n");

/**
 * Plan the note for a row.
 *
 * Every cell becomes a variable under its own (lowercased) name, so a view with a `Rating` column can write
 * `{{rating}}` in its template without anyone having to declare it — the view's columns already did.
 */
export function promotionPlan(input: PromotionInput): PromotionPlan {
  const matchValue = identityCell(input.cells, input.matchKey);

  const configured = (input.configuredFolder ?? "").trim().replace(/\/+$/, "");
  const scope = (input.scopeFolder ?? "").trim().replace(/\/+$/, "");
  const folder = configured !== "" ? configured : scope !== "" ? `${scope}/Notes` : "Notes";

  const title = titleCell(input.cells);
  const fileBase = safeName(title === "" ? "Note" : title);

  const variables: Record<string, string> = {};
  for (const [name, value] of Object.entries(input.cells)) {
    variables[name.trim().toLowerCase()] = value ?? "";
  }
  // The names templates expect, whatever the columns are called.
  if (variables["title"] === undefined || variables["title"] === "") variables["title"] = title;
  variables["source"] = variables[input.matchKey.toLowerCase()] ?? matchValue;
  if (variables["date"] === undefined) variables["date"] = new Date().toISOString().slice(0, 10);
  if (variables["annotations"] === undefined) variables["annotations"] = "";

  return {
    matchValue,
    folder,
    fileBase,
    variables,
    noteLinkColumn: noteLinkColumnName(
      input.columns.map((c) => ({ name: c.name, type: c.type ?? "text" })),
    ),
  };
}
