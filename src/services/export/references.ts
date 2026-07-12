import { splitAuthors } from "../../domain/columns/types/academic";

/** A normalised bibliographic reference assembled from a view row. */
export interface Reference {
  key: string;
  authors: string[];
  title: string;
  year: string;
  venue: string;
  doi: string;
  url: string;
  entryType: string; // article | inproceedings | book | misc
}

export interface ReferenceColumn {
  readonly name: string;
  readonly typeId: string;
}

const NAME_HINTS: Record<string, RegExp> = {
  title: /^(title|paper|name)$/i,
  year: /^(year|date|published|publication year)$/i,
  venue: /^(venue|journal|publication|publication title|booktitle|conference|source|proceedings)$/i,
  doi: /^doi$/i,
  authors: /^(authors?|by)$/i,
  url: /^(url|link)$/i,
  type: /^(type|entry ?type|item ?type)$/i,
};

function surname(author: string): string {
  return author.includes(",") ? author.split(",")[0]!.trim() : (author.split(/\s+/).pop() ?? author);
}

/** Map an entry/item-type string (incl. Zotero item types) to a BibTeX entry type. */
export function bibtexEntryType(raw: string): string {
  const t = raw.trim().toLowerCase();
  if (/conference|proceedings|inproceedings/.test(t)) return "inproceedings";
  if (/book(?!\s*section)/.test(t)) return "book";
  if (/chapter|section/.test(t)) return "incollection";
  if (/thesis|dissertation/.test(t)) return "phdthesis";
  if (/report/.test(t)) return "techreport";
  if (/web|blog|misc/.test(t)) return "misc";
  return "article";
}

/** Assemble a Reference from a row, using column types first, then column-name heuristics. */
export function rowToReference(columns: readonly ReferenceColumn[], cells: Readonly<Record<string, string>>): Reference {
  const lower = new Map(Object.entries(cells).map(([k, v]) => [k.toLowerCase(), v] as const));
  const get = (name: string | undefined): string => (name ? (lower.get(name.toLowerCase()) ?? "").trim() : "");
  const byType = (typeId: string): string | undefined => columns.find((c) => c.typeId === typeId)?.name;
  const byHint = (key: string): string | undefined => columns.find((c) => NAME_HINTS[key]!.test(c.name))?.name;

  const authorsRaw = get(byType("authors")) || get(byHint("authors"));
  const authors = splitAuthors(authorsRaw);
  const title = get(byHint("title"));
  const year = (get(byHint("year")).match(/\d{4}/)?.[0] ?? get(byHint("year"))).trim();
  const venue = get(byHint("venue"));
  const doi = get(byType("doi")) || get(byHint("doi"));
  const url = get(byType("url")) || get(byHint("url"));
  const entryType = bibtexEntryType(get(byHint("type")));

  let key = get(byType("citekey"));
  if (key === "") {
    const base = (authors[0] ? surname(authors[0]) : "ref").toLowerCase().replace(/[^a-z0-9]/g, "");
    key = `${base || "ref"}${year}`;
  }
  return { key: key.replace(/^@/, ""), authors, title, year, venue, doi, url, entryType };
}

function bibField(name: string, value: string): string | null {
  return value.trim() === "" ? null : `  ${name} = {${value.replace(/[{}]/g, "")}}`;
}

function bibEntry(ref: Reference): string {
  const fields = [
    ref.authors.length > 0 ? bibField("author", ref.authors.join(" and ")) : null,
    bibField("title", ref.title),
    bibField("year", ref.year),
    bibField(ref.entryType === "inproceedings" ? "booktitle" : "journal", ref.venue),
    bibField("doi", ref.doi),
    bibField("url", ref.url),
  ].filter((f): f is string => f !== null);
  return `@${ref.entryType}{${ref.key},\n${fields.join(",\n")}\n}`;
}

export function buildBibtex(refs: readonly Reference[]): string {
  return refs.map(bibEntry).join("\n\n") + (refs.length > 0 ? "\n" : "");
}

/** Names as "Surname, I." for APA. */
function apaAuthor(author: string): string {
  if (author.includes(",")) return author.trim();
  const parts = author.trim().split(/\s+/);
  if (parts.length === 1) return parts[0]!;
  const last = parts.pop()!;
  const initials = parts.map((p) => `${p[0]!.toUpperCase()}.`).join(" ");
  return `${last}, ${initials}`;
}

function apaAuthors(authors: readonly string[]): string {
  const list = authors.map(apaAuthor);
  if (list.length === 0) return "";
  if (list.length === 1) return list[0]!;
  if (list.length === 2) return `${list[0]}, & ${list[1]}`;
  return `${list.slice(0, -1).join(", ")}, & ${list[list.length - 1]}`;
}

function apa(ref: Reference): string {
  const parts: string[] = [];
  const who = apaAuthors(ref.authors);
  if (who) parts.push(`${who}`);
  if (ref.year) parts.push(`(${ref.year}).`);
  if (ref.title) parts.push(`${ref.title}.`);
  if (ref.venue) parts.push(`*${ref.venue}*.`);
  if (ref.doi) parts.push(`https://doi.org/${ref.doi.replace(/^https?:\/\/(dx\.)?doi\.org\//i, "")}`);
  else if (ref.url) parts.push(ref.url);
  return parts.join(" ").replace(/\s+/g, " ").trim();
}

function mla(ref: Reference): string {
  const parts: string[] = [];
  if (ref.authors.length > 0) parts.push(`${ref.authors[0]}${ref.authors.length > 1 ? ", et al" : ""}.`);
  if (ref.title) parts.push(`"${ref.title}."`);
  if (ref.venue) parts.push(`*${ref.venue}*,`);
  if (ref.year) parts.push(`${ref.year}.`);
  if (ref.doi) parts.push(`https://doi.org/${ref.doi.replace(/^https?:\/\/(dx\.)?doi\.org\//i, "")}.`);
  return parts.join(" ").replace(/\s+/g, " ").trim();
}

export type BibliographyStyle = "apa" | "mla";

export function buildBibliography(refs: readonly Reference[], style: BibliographyStyle): string {
  const entries = refs.map((r) => (style === "apa" ? apa(r) : mla(r))).filter((e) => e !== "");
  return entries.join("\n\n");
}
