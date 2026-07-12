/**
 * Look up bibliographic metadata for a DOI from Crossref, so a library row can fill itself in from
 * just a DOI. The network call is injected (a `UrlFetcher`) so the response parsing is unit-testable
 * and the domain stays free of Obsidian's `requestUrl`.
 */
export interface DoiMetadata {
  authors: string; // "Family, Given; Family, Given"
  title: string;
  year: string;
  venue: string;
  doi: string;
}

export type UrlFetcher = (url: string) => Promise<{ status: number; json?: unknown; text?: string }>;

/** Strip resolver prefixes to a bare DOI. */
export function normalizeDoi(raw: string): string {
  return raw
    .trim()
    .replace(/^doi:\s*/i, "")
    .replace(/^https?:\/\/(dx\.)?doi\.org\//i, "")
    .trim();
}

interface CrossrefName {
  given?: string;
  family?: string;
  name?: string;
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return typeof v === "object" && v !== null ? (v as Record<string, unknown>) : null;
}

function firstString(v: unknown): string {
  if (Array.isArray(v)) return typeof v[0] === "string" ? v[0] : "";
  return typeof v === "string" ? v : "";
}

function crossrefYear(message: Record<string, unknown>): string {
  for (const key of ["issued", "published", "published-print", "published-online"]) {
    const block = asRecord(message[key]);
    const parts = block ? (block["date-parts"] as unknown) : undefined;
    if (Array.isArray(parts) && Array.isArray(parts[0]) && typeof parts[0][0] === "number") {
      return String(parts[0][0]);
    }
  }
  return "";
}

function crossrefAuthors(message: Record<string, unknown>): string {
  const list = message["author"];
  if (!Array.isArray(list)) return "";
  return list
    .map((a) => {
      const name = a as CrossrefName;
      if (name.family) return name.given ? `${name.family}, ${name.given}` : name.family;
      return name.name ?? "";
    })
    .filter((s) => s.trim() !== "")
    .join("; ");
}

/** Parse a Crossref `/works/{doi}` JSON payload into DoiMetadata. */
export function parseCrossref(json: unknown): DoiMetadata | null {
  const root = asRecord(json);
  const message = root ? asRecord(root["message"]) : null;
  if (!message) return null;
  const title = firstString(message["title"]);
  const authors = crossrefAuthors(message);
  if (title === "" && authors === "") return null; // nothing usable
  return {
    authors,
    title,
    year: crossrefYear(message),
    venue: firstString(message["container-title"]) || firstString(message["publisher"]),
    doi: typeof message["DOI"] === "string" ? (message["DOI"] as string) : "",
  };
}

export async function fetchDoiMetadata(doi: string, fetch: UrlFetcher): Promise<DoiMetadata | null> {
  const res = await fetchDoiMetadataResult(doi, fetch);
  return res.ok ? res.meta : null;
}

export type DoiLookupResult = { ok: true; meta: DoiMetadata } | { ok: false; reason: string };

/** Like fetchDoiMetadata, but explains failures so the UI can show a useful notice. */
export async function fetchDoiMetadataResult(doi: string, fetch: UrlFetcher): Promise<DoiLookupResult> {
  const id = normalizeDoi(doi);
  if (id === "") return { ok: false, reason: "That doesn't look like a DOI — expected something like 10.1000/xyz123." };
  if (!/^10\.\d+\/\S+$/.test(id)) return { ok: false, reason: `“${id}” isn't a valid DOI (it should start with “10.” and a registrant code).` };
  let res: { status: number; json?: unknown; text?: string };
  try {
    res = await fetch(`https://api.crossref.org/works/${encodeURIComponent(id)}`);
  } catch {
    return { ok: false, reason: "Couldn't reach Crossref — check your internet connection and try again." };
  }
  if (res.status === 404) return { ok: false, reason: "Crossref has no record for that DOI. Double-check it, or the paper may not be indexed there." };
  if (res.status === 429) return { ok: false, reason: "Crossref is rate-limiting requests. Wait a moment (or raise the delay in settings) and try again." };
  if (res.status >= 500) return { ok: false, reason: `Crossref is having problems (status ${res.status}). Try again later.` };
  if (res.status !== 200) return { ok: false, reason: `Crossref returned status ${res.status}.` };
  let json: unknown;
  try {
    json = res.json ?? (res.text ? (JSON.parse(res.text) as unknown) : undefined);
  } catch {
    return { ok: false, reason: "Crossref replied, but the response wasn't readable JSON." };
  }
  const meta = parseCrossref(json);
  if (!meta) return { ok: false, reason: "Crossref replied, but had no usable metadata for that DOI." };
  return { ok: true, meta };
}
