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
}

export const DEFAULT_PROMOTED_TEMPLATE = [
  "---",
  'title: "{{title}}"',
  "authors:{{authorsList}}",
  'year: "{{year}}"',
  'venue: "{{venue}}"',
  'doi: "{{doi}}"',
  'citekey: "{{citekey}}"',
  "tags: [{{tags}}]",
  "---",
  "",
  "# {{title}}",
  "",
  "**Authors:** {{authors}}",
  "**Year:** {{year}} · **Venue:** {{venue}}",
  "**DOI:** {{doi}} · **Cite:** {{cite}}",
  "",
  "## Attachments",
  "",
  "```kvs-paper",
  "```",
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
export const PROMOTED_PLACEHOLDERS = ["title", "authors", "authorsList", "year", "venue", "doi", "citekey", "cite", "tags", "date"] as const;

function splitAuthorsLite(raw: string): string[] {
  return raw
    .split(/;|\band\b|&/i)
    .map((s) => s.trim())
    .filter((s) => s !== "");
}

/** Render a promoted-note template. Values are quote-sanitised so quoted YAML stays valid. */
export function renderPromotedNote(template: string, f: PromotedNoteFields, today = new Date()): string {
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
  };
  return template.replace(/\{\{(\w+)\}\}/g, (_m, key: string) => values[key.toLowerCase()] ?? "");
}
