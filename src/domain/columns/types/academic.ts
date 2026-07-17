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

/** The bare registrant prefix of a DOI, e.g. "10.1145", or null if it doesn't look like a DOI. */
export function doiPrefix(raw: string): string | null {
  const id = raw
    .trim()
    .replace(/^doi:\s*/i, "")
    .replace(/^https?:\/\/(dx\.)?doi\.org\//i, "");
  return /^(10\.\d{3,9})\//.exec(id)?.[1] ?? null;
}

/**
 * DOI prefix → publisher, for the common registrants across CS, physics, biology, medicine, chemistry and the
 * major generalist presses. This is deliberately an *offline* lookup (no network, no favicons): a curated map
 * that covers the prefixes a research vault actually accumulates, and falls back to the bare prefix for the
 * long tail. It's a display convenience, not an authority — prefixes occasionally change hands.
 */
const DOI_REGISTRANTS: Readonly<Record<string, string>> = {
  "10.1145": "ACM",
  "10.1109": "IEEE",
  "10.18653": "ACL",
  "10.1038": "Nature",
  "10.1126": "Science",
  "10.1016": "Elsevier",
  "10.1007": "Springer",
  "10.1002": "Wiley",
  "10.1111": "Wiley",
  "10.1017": "Cambridge UP",
  "10.1093": "Oxford UP",
  "10.1080": "Taylor & Francis",
  "10.1177": "SAGE",
  "10.1103": "APS",
  "10.1063": "AIP",
  "10.1088": "IOP",
  "10.1021": "ACS",
  "10.1039": "RSC",
  "10.1364": "Optica",
  "10.1073": "PNAS",
  "10.1371": "PLOS",
  "10.7554": "eLife",
  "10.1101": "Cold Spring Harbor",
  "10.48550": "arXiv",
  "10.1128": "ASM",
  "10.1074": "ASBMB",
  "10.1523": "Soc. for Neuroscience",
  "10.1015": "EMBO",
  "10.15252": "EMBO",
  "10.1084": "Rockefeller UP",
  "10.4049": "Amer. Assoc. Immunologists",
  "10.1056": "NEJM",
  "10.1001": "JAMA",
  "10.1136": "BMJ",
  "10.1161": "AHA",
  "10.1200": "ASCO",
  "10.2337": "Amer. Diabetes Assoc.",
  "10.3390": "MDPI",
  "10.1186": "BioMed Central",
  "10.1155": "Hindawi",
  "10.1098": "Royal Society",
  "10.1049": "IET",
  "10.5194": "Copernicus",
  "10.5281": "Zenodo",
  "10.1162": "MIT Press",
  "10.1215": "Duke UP",
  "10.1086": "Chicago UP",
  "10.2307": "JSTOR",
  "10.1075": "John Benjamins",
  "10.1057": "Palgrave",
  "10.1257": "Amer. Economic Assoc.",
  "10.1287": "INFORMS",
  "10.1190": "SEG",
  "10.1121": "Acoustical Soc.",
  "10.1044": "ASHA",
  "10.1105": "ASPB",
  "10.1042": "Portland Press",
  "10.1099": "Microbiology Soc.",
  "10.1113": "Physiological Soc.",
};

/** The publisher for a DOI from its prefix, or null if the prefix isn't in the offline map. */
export function doiRegistrant(raw: string): string | null {
  const prefix = doiPrefix(raw);
  return prefix ? (DOI_REGISTRANTS[prefix] ?? null) : null;
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
