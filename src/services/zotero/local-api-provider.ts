import {
  ReadOnlyZoteroBackend,
  type ZoteroAnnotationRecord,
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

  /**
   * Fetch every page of a paginated Zotero endpoint. Zotero's API caps a single request at 100 items, so a
   * library of any real size must be walked page by page. We don't have the `Total-Results` header here (the
   * fetcher exposes only the body), so we page by size: keep requesting `start=0, 100, 200, …` until a page
   * comes back shorter than the page size, which means it's the last one. A hard item cap guards against a
   * misbehaving server looping forever.
   */
  private async fetchAllPages(buildUrl: (start: number, pageSize: number) => string, maxItems: number): Promise<unknown[]> {
    const pageSize = 100; // Zotero's per-request maximum
    const concurrency = 6; // fetch several pages at once — the big win for a large library
    const out: unknown[] = [];
    // Fetch the first page alone to learn whether there's more than one page at all (most libraries fit a
    // few pages; this avoids firing a burst of requests for a tiny library).
    const first = await this.fetchPage(buildUrl(0, pageSize));
    if (first === null) return out;
    out.push(...first);
    if (first.length < pageSize) return out.slice(0, maxItems); // single page

    // Then fetch subsequent pages in concurrent batches, stopping when a batch returns a short/empty page.
    let start = pageSize;
    let done = false;
    while (!done && out.length < maxItems) {
      const starts: number[] = [];
      for (let i = 0; i < concurrency; i++) starts.push(start + i * pageSize);
      const pages = await Promise.all(starts.map((s) => this.fetchPage(buildUrl(s, pageSize))));
      for (const page of pages) {
        if (page === null || page.length === 0) {
          done = true;
          break;
        }
        out.push(...page);
        if (page.length < pageSize) {
          done = true;
          break;
        }
      }
      start += concurrency * pageSize;
    }
    return out.length > maxItems ? out.slice(0, maxItems) : out;
  }

  /** Fetch one page; null on any failure (so the caller stops paginating). */
  private async fetchPage(url: string): Promise<unknown[] | null> {
    const res = await this.fetcher(url);
    if (res.status < 200 || res.status >= 300 || !Array.isArray(res.json)) return null;
    return res.json as unknown[];
  }

  async listCollections(): Promise<ZoteroCollection[]> {
    const res = await this.fetcher(`${this.base}/collections?format=json&limit=200`);
    if (res.status < 200 || res.status >= 300 || !Array.isArray(res.json)) return [];
    return (res.json as unknown[]).map((c) => mapCollection(c)).filter((c): c is ZoteroCollection => c !== null);
  }

  async listItems(options: ZoteroListOptions = {}): Promise<ZoteroLibraryItem[]> {
    // No cap by default: the library view wants the whole library. `options.limit`, when given, is an
    // overall ceiling (e.g. search indexing bounds it), not a per-request size.
    const maxItems = options.limit ?? Number.MAX_SAFE_INTEGER;
    const basePath = options.collectionKey
      ? `${this.base}/collections/${encodeURIComponent(options.collectionKey)}/items/top`
      : `${this.base}/items/top`;
    const raw = await this.fetchAllPages((start, pageSize) => {
      const params = new URLSearchParams({ format: "json", limit: String(pageSize), start: String(start), itemType: "-attachment || note || annotation" });
      if (options.query) params.set("q", options.query);
      return `${basePath}?${params.toString()}`;
    }, maxItems);
    return raw.map((it) => mapItem(it)).filter((it): it is ZoteroLibraryItem => it !== null);
  }

  async getItem(key: string): Promise<ZoteroLibraryItem | null> {
    const res = await this.fetcher(`${this.base}/items/${encodeURIComponent(key)}?format=json`);
    if (res.status < 200 || res.status >= 300) return null;
    return mapItem(res.json);
  }

  async listAllAnnotations(): Promise<ZoteroAnnotationRecord[]> {
    // Walk every page of annotations — a heavily-annotated library easily exceeds one page.
    const raw = await this.fetchAllPages(
      (start, pageSize) => `${this.base}/items?itemType=annotation&format=json&limit=${pageSize}&start=${start}`,
      Number.MAX_SAFE_INTEGER,
    );
    return raw.map((a) => mapAnnotation(a)).filter((a): a is ZoteroAnnotationRecord => a !== null);
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

/**
 * The paper's cite key. Better BibTeX exposes this in more than one way depending on version and config, so
 * we check each: a dedicated `citationKey`/`citekey` field the newer BBT API injects, then the older
 * "Citation Key: xyz" line in `extra`. When none is present (no BBT, or an item BBT hasn't keyed), we
 * generate a reasonable one from the first author's surname + year + first title word — so a cite key always
 * comes through, which is what the "Fill from Zotero" flow needs.
 */
function resolveCiteKey(data: Record<string, unknown>, creators: string, year: string, title: string): string {
  const field = asString(data["citationKey"]) || asString(data["citekey"]) || asString(data["citation-key"]);
  if (field) return field;
  const fromExtra = citeKeyFromExtra(asString(data["extra"]));
  if (fromExtra) return fromExtra;
  return generateCiteKey(creators, year, title);
}

/** The cite key Better BibTeX stashes in `extra` as "Citation Key: xyz", if present. */
function citeKeyFromExtra(extra: string): string {
  const m = /Citation Key:\s*(\S+)/i.exec(extra);
  return m ? m[1]! : "";
}

/** Fallback cite key: firstAuthorSurname + year + firstTitleWord, lowercased and ASCII-cleaned. */
function generateCiteKey(creators: string, year: string, title: string): string {
  const clean = (s: string): string => s.normalize("NFKD").replace(/[^a-zA-Z0-9]/g, "").toLowerCase();
  // "Smith, Jones, and Lee" → "smith"; "The Transformer Team" → "the".
  const firstAuthor = clean((creators.split(/,| and /)[0] ?? "").trim().split(/\s+/).pop() ?? "");
  const firstTitleWord = clean((title.split(/\s+/).find((w) => w.length > 2) ?? "").trim());
  const key = `${firstAuthor}${year}${firstTitleWord}`;
  return key || "";
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

/** Flatten a Zotero annotation item (child item, itemType "annotation") for search indexing. */
export function mapAnnotation(raw: unknown): ZoteroAnnotationRecord | null {
  if (typeof raw !== "object" || raw === null) return null;
  const rec = raw as Record<string, unknown>;
  const data = (rec["data"] as Record<string, unknown> | undefined) ?? {};
  const key = asString(data["key"]);
  if (!key || asString(data["itemType"]) !== "annotation") return null;
  const text = asString(data["annotationText"]);
  const comment = asString(data["annotationComment"]);
  // An annotation with neither quoted text nor a comment has nothing to search; skip it.
  if (text === "" && comment === "") return null;
  return {
    key,
    parentKey: asString(data["parentItem"]),
    type: asString(data["annotationType"]) || "highlight",
    text,
    comment,
    pageLabel: asString(data["annotationPageLabel"]),
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
    citeKey: resolveCiteKey(data, formatCreators(data["creators"]), yearFromDate(date), asString(data["title"])),
    attachmentKeys: [], // filled by a follow-up children fetch only when a reader needs them
    extra,
  };
}
