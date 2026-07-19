import { renderTemplate } from "../../../shared/template";
/**
 * The template for a promoted paper note. A default is provided; users can override it in settings
 * with a simple {{placeholder}} template. Substitution is pure + unit-testable.
 */
export interface PromotedNoteFields {
  title: string;
  authors: string; // "; "-separated
  year: string;
  venue: string;
  doi: string;
  citekey: string;
  tags: readonly string[]; // tag names without '#'
  abstract?: string; // filled when the paper is in Zotero
  annotations?: string; // rendered annotations markdown, when the paper is in Zotero
  zoteroKey?: string; // the Zotero item key, when the paper is in Zotero (links the note back)
}

export const DEFAULT_PROMOTED_TEMPLATE = [
  "---",
  'title: "{{title}}"',
  "authors:{{authorsList}}",
  'year: "{{year}}"',
  'venue: "{{venue}}"',
  'doi: "{{doi}}"',
  'citekey: "{{citekey}}"',
  'zotero-key: "{{zoteroKey}}"',
  "tags: [{{tags}}]",
  "---",
  "",
  "# {{title}}",
  "",
  "**Authors:** {{authors}}",
  "**Year:** {{year}} · **Venue:** {{venue}}",
  "**DOI:** {{doi}} · **Cite:** {{cite}}",
  "",
  "## Abstract",
  "",
  "{{abstract}}",
  "",
  "## Attachments",
  "",
  "```kvs-paper",
  "```",
  "",
  "## Annotations",
  "",
  "{{annotations}}",
  "",
  "## Notes",
  "",
  "",
  "## Findings",
  "",
  "| Paper | Theme | Finding | Evidence |",
  "| --- | --- | --- | --- |",
  "| {{citekey}} |  |  |  |",
  "",
].join("\n");

/** Placeholders shown in the settings help. */
export const PROMOTED_PLACEHOLDERS = ["title", "authors", "authorsList", "year", "venue", "doi", "citekey", "cite", "tags", "date", "abstract", "annotations", "zoteroKey"] as const;

function splitAuthorsLite(raw: string): string[] {
  return raw
    .split(/;|\band\b|&/i)
    .map((s) => s.trim())
    .filter((s) => s !== "");
}

/**
 * The variables a promoted-note template may refer to.
 *
 * Exposed separately from rendering so the bridge can offer the same set to a companion preview, and so a
 * test can pin exactly what a template author is promised.
 */
export function promotedNoteVariables(f: PromotedNoteFields, today = new Date()): Record<string, string> {
  const q = (s: string): string => s.replace(/"/g, "'"); // keep quoted YAML fields valid
  const authorList = splitAuthorsLite(f.authors);
  const authors = authorList.join(", ");
  // A YAML block list so Obsidian shows each author as a separate property value. Leading newline lets
  // the template write `authors:{{authorsList}}` and get a proper list (or nothing when there are none).
  const authorsList = authorList.length > 0 ? "\n" + authorList.map((a) => `  - "${q(a)}"`).join("\n") : "";
  const tagNames = ["paper", ...f.tags.map((t) => t.replace(/^#/, "").trim()).filter((t) => t !== "")];
  const values: Record<string, string> = {
    title: q(f.title),
    authors: q(authors),
    authorslist: authorsList,
    year: q(f.year),
    venue: q(f.venue),
    doi: q(f.doi),
    citekey: q(f.citekey),
    cite: f.citekey.trim() === "" ? "" : `[@${f.citekey.replace(/^@/, "")}]`,
    tags: [...new Set(tagNames)].join(", "),
    date: today.toISOString().slice(0, 10),
    abstract: f.abstract ?? "",
    annotations: f.annotations ?? "",
    zoterokey: f.zoteroKey ?? "",
  };
  return values;
}

/**
 * Render a promoted-note template.
 *
 * Now the shared engine — the same one captured notes and the companion's previews use — rather than a
 * private substitution. Every old template keeps working: the old syntax only ever matched bare
 * `{{word}}` placeholders, which the shared engine resolves identically (case-insensitively, unknown
 * names to nothing). What changes is what *else* a template may now say: `{{title|truncate:60}}`,
 * `{{doi|wikilink}}` and the rest of the filter registry work here too, and two syntaxes for one job can
 * no longer drift apart.
 */
export function renderPromotedNote(template: string, f: PromotedNoteFields, today = new Date()): string {
  return renderTemplate(template, promotedNoteVariables(f, today));
}
