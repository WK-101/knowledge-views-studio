import { normalizeDoi } from "./doi-lookup";
import type { UrlFetcher } from "./doi-lookup";
import { asString } from "../../util/coerce";

/**
 * Find real citation edges among papers you own, via OpenAlex. Given a paper's DOI, OpenAlex returns
 * the OpenAlex ids of the works it references; we resolve your library's DOIs to OpenAlex ids once,
 * then intersect — so "Cites" links point only at papers already in your library. The network call is
 * injected so the JSON parsing is unit-testable and the module stays Obsidian-free.
 */

/** Extract the short OpenAlex id ("W123…") from a full URL or bare id. */
export function shortOpenAlexId(idOrUrl: string): string {
  const m = /(W\d+)/.exec(idOrUrl);
  return m ? m[1]! : "";
}

interface Rec {
  readonly [k: string]: unknown;
}
function asRecord(v: unknown): Rec | null {
  return typeof v === "object" && v !== null ? (v as Rec) : null;
}

/** Parse a work's `referenced_works` into a set of short OpenAlex ids. */
export function parseReferencedIds(json: unknown): Set<string> {
  const work = asRecord(json);
  const refs = work ? work["referenced_works"] : undefined;
  const out = new Set<string>();
  if (Array.isArray(refs)) {
    for (const r of refs) {
      const id = shortOpenAlexId(String(r));
      if (id) out.add(id);
    }
  }
  return out;
}

/** Parse a works-list response into [shortId, normalizedDoi] pairs. */
export function parseWorksList(json: unknown): { id: string; doi: string }[] {
  const root = asRecord(json);
  const results = root ? root["results"] : undefined;
  const out: { id: string; doi: string }[] = [];
  if (Array.isArray(results)) {
    for (const r of results) {
      const rec = asRecord(r);
      if (!rec) continue;
      const id = shortOpenAlexId(asString(rec["id"]));
      const doi = rec["doi"] ? normalizeDoi(asString(rec["doi"])).toLowerCase() : "";
      if (id && doi) out.push({ id, doi });
    }
  }
  return out;
}

/** Fetch the OpenAlex ids referenced by a work (by DOI). Empty set on any failure. */
export async function fetchReferencedIds(doi: string, fetch: UrlFetcher): Promise<Set<string>> {
  const id = normalizeDoi(doi);
  if (id === "") return new Set();
  try {
    const res = await fetch(`https://api.openalex.org/works/doi:${encodeURIComponent(id)}?select=id,doi,referenced_works`);
    if (res.status !== 200) return new Set();
    return parseReferencedIds(res.json ?? (res.text ? JSON.parse(res.text) : undefined));
  } catch {
    return new Set();
  }
}

/** Resolve a set of DOIs to their OpenAlex ids (batched by 50). Returns doi → shortId. */
export async function resolveOpenAlexIds(dois: readonly string[], fetch: UrlFetcher): Promise<Map<string, string>> {
  const byDoi = new Map<string, string>();
  const clean = [...new Set(dois.map((d) => normalizeDoi(d).toLowerCase()).filter((d) => d !== ""))];
  for (let i = 0; i < clean.length; i += 50) {
    const chunk = clean.slice(i, i + 50);
    try {
      const filter = `doi:${chunk.map((d) => encodeURIComponent(d)).join("|")}`;
      const res = await fetch(`https://api.openalex.org/works?filter=${filter}&select=id,doi&per-page=50`);
      if (res.status !== 200) continue;
      for (const { id, doi } of parseWorksList(res.json ?? (res.text ? JSON.parse(res.text) : undefined))) byDoi.set(doi, id);
    } catch {
      // skip this chunk
    }
  }
  return byDoi;
}
