import type { SearchHit, SearchRequest, SearchResponse } from "../bridge/types";
import type { SearchIndexer } from "../../workspace/search-indexer";
import type { SearchResult } from "../index";

/**
 * Presenting search results to the browser.
 *
 * The index speaks in document ids and metadata; the companion needs something a person can read in a
 * popup. This is the translation, kept apart from the route so the route stays about permissions and shapes
 * rather than about how a heading becomes a subtitle.
 *
 * Snippets are fetched only for the handful of results actually being returned. The full text of a matched
 * PDF can be very large, and a popup showing twenty results has no use for twenty documents.
 */

const SNIPPET_LENGTH = 220;

function metaString(result: SearchResult, key: string): string {
  const value = result.meta?.[key];
  return typeof value === "string" ? value : typeof value === "number" ? String(value) : "";
}

/** The best human-readable name for a hit, falling back through the things that might carry one. */
function titleOf(result: SearchResult): string {
  const explicit = metaString(result, "title");
  if (explicit !== "") return explicit;
  const path = metaString(result, "path");
  if (path !== "") {
    const base = path.split("/").pop() ?? path;
    return base.replace(/\.[^.]+$/, "");
  }
  return metaString(result, "url") || result.id;
}

/** Heading, page or section — whatever locates the hit inside its file. */
function locationOf(result: SearchResult): string {
  const heading = metaString(result, "heading");
  if (heading !== "") return heading;
  const section = metaString(result, "section");
  if (section !== "") return section;
  return result.location ?? "";
}

/**
 * Cut a snippet around the first query term, so the fragment shown is the part that matched rather than
 * whatever happened to be at the start of the document.
 */
export function snippetAround(text: string, terms: readonly string[], length = SNIPPET_LENGTH): string {
  const flat = text.replace(/\s+/g, " ").trim();
  if (flat === "") return "";
  if (flat.length <= length) return flat;

  const lower = flat.toLowerCase();
  let at = -1;
  for (const term of terms) {
    const cleaned = term.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
    if (cleaned.length < 2) continue;
    const found = lower.indexOf(cleaned);
    if (found >= 0) {
      at = found;
      break;
    }
  }
  if (at < 0) return `${flat.slice(0, length)}…`;

  const start = Math.max(0, at - Math.floor(length / 3));
  const end = Math.min(flat.length, start + length);
  return `${start > 0 ? "…" : ""}${flat.slice(start, end)}${end < flat.length ? "…" : ""}`;
}

function toHit(result: SearchResult, snippet: string): SearchHit {
  const path = metaString(result, "path");
  const url = metaString(result, "url");
  const location = locationOf(result);
  return {
    id: result.id,
    title: titleOf(result),
    source: result.source,
    ...(path !== "" ? { path } : {}),
    ...(location !== "" ? { location } : {}),
    ...(snippet !== "" ? { snippet } : {}),
    ...(url !== "" ? { url } : {}),
    score: result.score,
  };
}

/**
 * Run a search and shape it for the wire.
 *
 * "Ask" is handled separately because it isn't a ranked document list — it returns the passages that answer
 * the question, and those passages *are* the snippets, so there's nothing to fetch afterwards.
 */
export async function runBridgeSearch(indexer: SearchIndexer, request: SearchRequest): Promise<SearchResponse> {
  const query = request.query.trim();
  const limit = request.limit ?? 20;
  const mode = request.mode ?? "keyword";
  const terms = query.split(/\s+/).filter((t) => t !== "");

  if (mode === "ask") {
    const passages = await indexer.answer(query, Math.min(limit, 8));
    const hits: SearchHit[] = passages.map((passage) => {
      const asResult: SearchResult = {
        id: passage.docId,
        score: passage.score,
        source: passage.source,
        ...(passage.location !== undefined ? { location: passage.location } : {}),
        ...(passage.meta !== undefined ? { meta: passage.meta } : {}),
      };
      return toHit(asResult, passage.text.replace(/\s+/g, " ").trim());
    });
    return { mode, hits };
  }

  const results =
    mode === "semantic" ? indexer.semanticSearch(query, limit) : indexer.search(query, { limit });

  const hits: SearchHit[] = [];
  for (const result of results.slice(0, limit)) {
    let snippet = "";
    try {
      const text = await indexer.getText(result.id);
      if (text !== undefined) snippet = snippetAround(text, terms);
    } catch {
      // A missing snippet is a cosmetic loss; the hit itself is still worth returning.
    }
    hits.push(toHit(result, snippet));
  }
  return { mode, hits };
}
