import {
  ReadOnlyZoteroBackend,
  type ZoteroCollection,
  type ZoteroFetcher,
  type ZoteroLibraryItem,
  type ZoteroListOptions,
  type ZoteroProvider,
  type ZoteroWriteBackend,
} from "./provider";

/**
 * Reads a Zotero library over the local HTTP API (localhost:23119) — the same live source the ZotFlow
 * plugin uses, and a strict upgrade over reading a stale Better BibTeX JSON export the way zotero-lib-view
 * does: the data is always current, with no manual re-export step.
 *
 * Everything here is read-only, because the local API is read-only. The write backend is injected (default
 * {@link ReadOnlyZoteroBackend}) rather than assumed, so the day a real one exists it is passed in here and
 * this class is untouched.
 *
 * All parsing is defensive: the API is another program's output and its exact JSON shape can shift between
 * Zotero versions, so a missing or odd field yields an empty string, never a throw.
 */
export class LocalApiZoteroProvider implements ZoteroProvider {
  readonly writes: ZoteroWriteBackend;
  private readonly base: string;

  constructor(
    baseUrl: string,
    private readonly fetcher: ZoteroFetcher,
    writes: ZoteroWriteBackend = new ReadOnlyZoteroBackend(),
  ) {
    this.base = baseUrl.replace(/\/+$/, "");
    this.writes = writes;
  }

  async ping(): Promise<boolean> {
    try {
      const res = await this.fetcher(`${this.base}/items?limit=1&format=json`);
      return res.status >= 200 && res.status < 300;
    } catch {
      return false;
    }
  }

  async listCollections(): Promise<ZoteroCollection[]> {
    const res = await this.fetcher(`${this.base}/collections?format=json&limit=200`);
    if (res.status < 200 || res.status >= 300 || !Array.isArray(res.json)) return [];
    return (res.json as unknown[]).map((c) => mapCollection(c)).filter((c): c is ZoteroCollection => c !== null);
  }

  async listItems(options: ZoteroListOptions = {}): Promise<ZoteroLibraryItem[]> {
    const limit = options.limit ?? 200;
    const params = new URLSearchParams({ format: "json", limit: String(limit), itemType: "-attachment || note || annotation" });
    if (options.query) params.set("q", options.query);
    const path = options.collectionKey
      ? `${this.base}/collections/${encodeURIComponent(options.collectionKey)}/items/top`
      : `${this.base}/items/top`;
    const res = await this.fetcher(`${path}?${params.toString()}`);
    if (res.status < 200 || res.status >= 300 || !Array.isArray(res.json)) return [];
    return (res.json as unknown[]).map((it) => mapItem(it)).filter((it): it is ZoteroLibraryItem => it !== null);
  }

  async getItem(key: string): Promise<ZoteroLibraryItem | null> {
    const res = await this.fetcher(`${this.base}/items/${encodeURIComponent(key)}?format=json`);
    if (res.status < 200 || res.status >= 300) return null;
    return mapItem(res.json);
  }
}

// ---------------------------------------------------------------------------
// Defensive mapping of the local API's JSON
// ---------------------------------------------------------------------------

function asString(v: unknown): string {
  return typeof v === "string" ? v : typeof v === "number" ? String(v) : "";
}

function libraryIdOf(rec: Record<string, unknown>): number {
  const lib = rec["library"];
  if (typeof lib === "object" && lib !== null) {
    const id = (lib as Record<string, unknown>)["id"];
    if (typeof id === "number") return id;
  }
  return 0;
}

/** Zotero creators → "Smith, Jones, and Lee". Handles both {firstName,lastName} and {name} shapes. */
function formatCreators(creators: unknown): string {
  if (!Array.isArray(creators)) return "";
  const names = creators
    .map((c) => {
      if (typeof c !== "object" || c === null) return "";
      const r = c as Record<string, unknown>;
      const last = asString(r["lastName"]);
      if (last) return last;
      return asString(r["name"]);
    })
    .filter((n) => n !== "");
  if (names.length === 0) return "";
  if (names.length === 1) return names[0]!;
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return `${names.slice(0, -1).join(", ")}, and ${names[names.length - 1]}`;
}

function yearFromDate(date: string): string {
  const m = /\b(\d{4})\b/.exec(date);
  return m ? m[1]! : "";
}

/** The cite key Better BibTeX stashes in `extra` as "Citation Key: xyz", if present. */
function citeKeyFromExtra(extra: string): string {
  const m = /Citation Key:\s*(\S+)/i.exec(extra);
  return m ? m[1]! : "";
}

function mapCollection(raw: unknown): ZoteroCollection | null {
  if (typeof raw !== "object" || raw === null) return null;
  const rec = raw as Record<string, unknown>;
  const data = (rec["data"] as Record<string, unknown> | undefined) ?? {};
  const meta = (rec["meta"] as Record<string, unknown> | undefined) ?? {};
  const key = asString(data["key"]);
  if (!key) return null;
  const parent = data["parentCollection"];
  return {
    key,
    name: asString(data["name"]),
    parentKey: typeof parent === "string" && parent !== "" ? parent : null,
    itemCount: typeof meta["numItems"] === "number" ? meta["numItems"] : 0,
  };
}

/** Map one Zotero item envelope into a library item. Returns null for items with no key. */
export function mapItem(raw: unknown): ZoteroLibraryItem | null {
  if (typeof raw !== "object" || raw === null) return null;
  const rec = raw as Record<string, unknown>;
  const data = (rec["data"] as Record<string, unknown> | undefined) ?? {};

  const key = asString(data["key"]);
  if (!key) return null;

  const date = asString(data["date"]);
  const extraStr = asString(data["extra"]);

  // Promote the fields a library view shows; keep the rest in `extra` so a column can bind to anything.
  const promoted = new Set([
    "key", "version", "itemType", "title", "creators", "date", "DOI", "url", "tags", "collections",
    "dateAdded", "dateModified", "extra", "abstractNote",
  ]);
  const extra: Record<string, string> = {};
  for (const [k, v] of Object.entries(data)) {
    if (!promoted.has(k)) {
      const s = asString(v);
      if (s !== "") extra[k] = s;
    }
  }
  if (extraStr) extra["extra"] = extraStr;
  const abstractNote = asString(data["abstractNote"]);
  if (abstractNote) extra["abstract"] = abstractNote;

  const tags = Array.isArray(data["tags"])
    ? (data["tags"] as unknown[]).map((t) => (typeof t === "object" && t !== null ? asString((t as Record<string, unknown>)["tag"]) : "")).filter((t) => t !== "")
    : [];
  const collections = Array.isArray(data["collections"]) ? (data["collections"] as unknown[]).map(asString).filter((c) => c !== "") : [];

  // Publication title lives under different keys by item type; take the first that has a value.
  const publication =
    asString(data["publicationTitle"]) || asString(data["bookTitle"]) || asString(data["proceedingsTitle"]) ||
    asString(data["journalAbbreviation"]) || asString(data["publisher"]);

  return {
    key,
    libraryId: libraryIdOf(rec),
    version: typeof data["version"] === "number" ? data["version"] : typeof rec["version"] === "number" ? rec["version"] : 0,
    itemType: asString(data["itemType"]),
    title: asString(data["title"]),
    creators: formatCreators(data["creators"]),
    year: yearFromDate(date),
    publication,
    doi: asString(data["DOI"]),
    url: asString(data["url"]),
    tags,
    collections,
    dateAdded: asString(data["dateAdded"]),
    dateModified: asString(data["dateModified"]),
    citeKey: citeKeyFromExtra(extraStr),
    attachmentKeys: [], // filled by a follow-up children fetch only when a reader needs them
    extra,
  };
}
