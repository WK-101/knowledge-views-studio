import type { ColumnType } from "../column-type";

/** Split an author string into individual authors on ";", " and ", or "&". */
export function splitAuthors(raw: string): string[] {
  return String(raw ?? "")
    .split(/\s*;\s*|\s+and\s+|\s*&\s*/i)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

/** The surname of one author, handling "Last, First" and "First Last" forms. */
function surname(author: string): string {
  if (author.includes(",")) return author.split(",")[0]!.trim();
  const parts = author.split(/\s+/);
  return parts[parts.length - 1] ?? author;
}

/** Short citation form: "Smith", "Smith & Jones", or "Smith et al." */
export function formatAuthorsShort(raw: string): string {
  const authors = splitAuthors(raw);
  if (authors.length === 0) return "";
  if (authors.length === 1) return surname(authors[0]!);
  if (authors.length === 2) return `${surname(authors[0]!)} & ${surname(authors[1]!)}`;
  return `${surname(authors[0]!)} et al.`;
}

/** Normalise a DOI (strip "doi:" and any resolver prefix) then build its doi.org URL. */
export function doiUrl(raw: string): string {
  const id = raw
    .trim()
    .replace(/^doi:\s*/i, "")
    .replace(/^https?:\/\/(dx\.)?doi\.org\//i, "");
  return `https://doi.org/${id}`;
}

export function arxivUrl(raw: string): string {
  const id = raw.trim().replace(/^arxiv:\s*/i, "").replace(/^https?:\/\/arxiv\.org\/abs\//i, "");
  return `https://arxiv.org/abs/${id}`;
}

export function pmidUrl(raw: string): string {
  const id = raw.trim().replace(/[^0-9]/g, "");
  return `https://pubmed.ncbi.nlm.nih.gov/${id}/`;
}

/** A plain string-like type with a distinct id so it can carry academic rendering + actions. */
function referenceType(id: string, label: string): ColumnType {
  return {
    id,
    label,
    operators: ["contains", "not-contains", "equals", "not-equals", "is-empty", "is-not-empty"],
    isEmpty: (raw) => String(raw ?? "").trim() === "",
    toComparable: (raw) => ({ kind: "string", value: String(raw ?? "").trim().toLowerCase() }),
    toPlainText: (raw) => String(raw ?? "").trim(),
    validate: () => null,
  };
}

export const DOI: ColumnType = referenceType("doi", "DOI");
export const CITEKEY: ColumnType = referenceType("citekey", "Citation key");
export const ARXIV: ColumnType = referenceType("arxiv", "arXiv ID");
export const PMID: ColumnType = referenceType("pmid", "PubMed ID");

export const AUTHORS: ColumnType = {
  id: "authors",
  label: "Authors",
  operators: ["contains", "not-contains", "is-empty", "is-not-empty"],
  isEmpty: (raw) => splitAuthors(raw).length === 0,
  toComparable: (raw) => ({ kind: "string", value: splitAuthors(raw).map(surname).join(" ").toLowerCase() }),
  toPlainText: (raw) => splitAuthors(raw).join("; "),
  validate: () => null,
};

/** The academic kit's column types, exposed in the editor only for kit-enabled views. */
export const ACADEMIC_COLUMN_TYPES: readonly ColumnType[] = [CITEKEY, AUTHORS, DOI, ARXIV, PMID];
export const ACADEMIC_TYPE_IDS: ReadonlySet<string> = new Set(ACADEMIC_COLUMN_TYPES.map((t) => t.id));
