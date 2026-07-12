/**
 * Parse a reference export (BibTeX or CSV, e.g. from Zotero) into normalised rows, then render them
 * as a Markdown papers table that a KVS academic view can aggregate. Parsing is intentionally lenient:
 * academic exports vary, so we extract what we can and leave the rest blank.
 */
export interface ImportedRef {
  citeKey: string;
  authors: string; // "; "-separated
  year: string;
  title: string;
  venue: string;
  doi: string;
  itemType: string;
  abstract: string;
  tags: string; // space-separated #hashtags
}

/** Turn a free-text keyword/tag into an Obsidian-safe hashtag ("machine learning" → "#machine-learning"). */
function toHashtag(raw: string): string {
  const slug = raw.trim().replace(/^#/, "").replace(/\s+/g, "-").replace(/[^\p{L}\p{N}/_-]/gu, "");
  return slug === "" ? "" : `#${slug}`;
}

/** Build a space-separated hashtag string from a list of raw keyword strings. */
function tagsFrom(raw: string, split: RegExp): string {
  return raw
    .split(split)
    .map(toHashtag)
    .filter((t) => t !== "")
    .join(" ");
}

// ---- BibTeX ----------------------------------------------------------------

function cleanBibValue(v: string): string {
  return v
    .replace(/[{}]/g, "")
    .replace(/\\[a-zA-Z]+/g, "")
    .replace(/~/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function parseBibFields(text: string): Record<string, string> {
  const fields: Record<string, string> = {};
  let i = 0;
  while (i < text.length) {
    const eq = text.indexOf("=", i);
    if (eq < 0) break;
    const name = text.slice(i, eq).replace(/[,\s]/g, "").toLowerCase();
    let k = eq + 1;
    while (k < text.length && /\s/.test(text[k]!)) k++;
    let value = "";
    if (text[k] === "{") {
      let depth = 1;
      k++;
      const start = k;
      while (k < text.length && depth > 0) {
        if (text[k] === "{") depth++;
        else if (text[k] === "}") depth--;
        if (depth > 0) k++;
      }
      value = text.slice(start, k);
      k++;
    } else if (text[k] === '"') {
      k++;
      const start = k;
      while (k < text.length && text[k] !== '"') k++;
      value = text.slice(start, k);
      k++;
    } else {
      const start = k;
      while (k < text.length && text[k] !== "," && text[k] !== "\n") k++;
      value = text.slice(start, k).trim();
    }
    if (name) fields[name] = cleanBibValue(value);
    const nextComma = text.indexOf(",", k);
    if (nextComma < 0) break;
    i = nextComma + 1;
  }
  return fields;
}

function bibAuthors(raw: string): string {
  return raw
    .split(/\s+and\s+/i)
    .map((a) => a.trim())
    .filter(Boolean)
    .join("; ");
}

export function parseBibtex(text: string): ImportedRef[] {
  const out: ImportedRef[] = [];
  let i = 0;
  while (i < text.length) {
    const at = text.indexOf("@", i);
    if (at < 0) break;
    const brace = text.indexOf("{", at);
    if (brace < 0) break;
    const itemType = text.slice(at + 1, brace).trim().toLowerCase();
    let depth = 1;
    let j = brace + 1;
    while (j < text.length && depth > 0) {
      if (text[j] === "{") depth++;
      else if (text[j] === "}") depth--;
      j++;
    }
    const body = text.slice(brace + 1, j - 1);
    i = j;
    if (itemType === "comment" || itemType === "preamble" || itemType === "string") continue;
    const comma = body.indexOf(",");
    if (comma < 0) continue;
    const key = body.slice(0, comma).trim();
    const f = parseBibFields(body.slice(comma + 1));
    out.push({
      citeKey: key,
      authors: bibAuthors(f.author ?? f.editor ?? ""),
      year: (f.year ?? f.date ?? "").match(/\d{4}/)?.[0] ?? (f.year ?? ""),
      title: f.title ?? "",
      venue: f.journal ?? f.booktitle ?? f.publisher ?? "",
      doi: f.doi ?? "",
      itemType,
      abstract: f.abstract ?? "",
      tags: tagsFrom(f.keywords ?? f.keyword ?? "", /[,;]+/),
    });
  }
  return out;
}

// ---- CSV -------------------------------------------------------------------

function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else inQuotes = false;
      } else field += c;
    } else if (c === '"') {
      inQuotes = true;
    } else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += c;
  }
  if (field !== "" || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

function parseReferenceCsv(text: string): ImportedRef[] {
  const rows = parseCsv(text).filter((r) => r.some((c) => c.trim() !== ""));
  if (rows.length < 2) return [];
  const header = rows[0]!.map((h) => h.trim().toLowerCase());
  const col = (...names: string[]): number => header.findIndex((h) => names.includes(h));
  const kI = col("key", "citation key", "bibtex key", "cite key");
  const aI = col("author", "authors", "creator");
  const yI = col("publication year", "year", "date");
  const tI = col("title");
  const vI = col("publication title", "journal", "publication", "conference name", "proceedings title", "book title");
  const dI = col("doi");
  const iI = col("item type", "type");
  const abI = col("abstract", "abstract note");
  const mtI = col("manual tags");
  const atI = col("automatic tags");
  const at = (r: string[], idx: number): string => (idx >= 0 ? (r[idx] ?? "").trim() : "");
  return rows.slice(1).map((r) => ({
    citeKey: at(r, kI),
    authors: at(r, aI).replace(/\s*;\s*/g, "; "),
    year: at(r, yI).match(/\d{4}/)?.[0] ?? at(r, yI),
    title: at(r, tI),
    venue: at(r, vI),
    doi: at(r, dI),
    itemType: at(r, iI),
    abstract: at(r, abI),
    tags: tagsFrom([at(r, mtI), at(r, atI)].filter((x) => x !== "").join(";"), /[;,]+/),
  }));
}

/** Auto-detect and parse a reference export. */
export function parseReferences(text: string): ImportedRef[] {
  return text.trim().startsWith("@") ? parseBibtex(text) : parseReferenceCsv(text);
}

const esc = (v: string): string => v.replace(/\|/g, "\\|").replace(/\r?\n/g, " ").trim();

/** Render imported references as a Markdown note body with a papers table + a short intro. */
export function referencesToNote(refs: readonly ImportedRef[]): string {
  const header = "| Cite key | Authors | Year | Title | Venue | Tags | Summary | DOI |";
  const divider = "| --- | --- | --- | --- | --- | --- | --- | --- |";
  const lines = refs.map(
    (r) =>
      `| ${esc(r.citeKey)} | ${esc(r.authors)} | ${esc(r.year)} | ${esc(r.title)} | ${esc(r.venue)} | ${esc(r.tags)} | ${esc(r.abstract)} | ${esc(r.doi)} |`,
  );
  return ["# Imported references", "", `${refs.length} reference(s) imported. Edit inline; each cell writes back here.`, "", header, divider, ...lines, ""].join("\n");
}
