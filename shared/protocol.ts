/**
 * The wire contract between the KVS plugin and its browser companion.
 *
 * The single source of truth for both sides. The plugin re-exports these and the extension imports them
 * directly, so a change to a shape is a compile error in whichever half hasn't kept up — which is the whole
 * reason the two live in one repository rather than drifting apart in two.
 */

export interface SchemaColumn {
  readonly name: string;
  readonly typeId: string;
  readonly role?: string;
  /** Present for choice columns: the vocabulary this column already uses. */
  readonly options?: readonly string[];
}

export interface SchemaView {
  readonly id: string;
  readonly name: string;
  readonly columns: readonly SchemaColumn[];
  /** Whether this view can currently receive a capture, and in what shape. */
  readonly capture: { readonly writable: boolean; readonly shape?: "row" | "note"; readonly reason?: string };
}

export interface SchemaResponse {
  readonly vault: string;
  readonly protocol: number;
  readonly pluginVersion?: string;
  readonly views: readonly SchemaView[];
}

export interface LookupRequest {
  readonly url?: string;
  readonly doi?: string;
  readonly viewIds?: readonly string[];
}

export interface LookupMatch {
  readonly viewId: string;
  readonly viewName: string;
  /** Opaque handle for editing this row. Meaningless outside the vault that issued it. */
  readonly rowRef?: string;
  /** Whether this row already has a dedicated note, so a surface can offer "open" rather than "create". */
  readonly hasNote?: boolean;
  readonly on: string;
  readonly title: string;
  readonly filePath: string;
}

export interface CaptureRequest {
  readonly viewId: string;
  readonly fields: readonly { readonly key: string; readonly value: string }[];
  readonly url?: string;
  /**
   * Several rows at once, for a page that lists many things — a contents page, a search result, a
   * bibliography. When present, `fields` is ignored. Written in one file operation rather than one per row,
   * so a partial failure can't leave half a table behind.
   */
  readonly rows?: readonly (readonly { readonly key: string; readonly value: string }[])[];
  /**
   * Note-shaped capture. Present when the caller wants a note rather than a row, carrying the article body
   * it extracted — which the plugin can't obtain for itself, since a re-fetch would miss anything rendered
   * by script, expanded by the reader, or behind a login.
   */
  readonly note?: {
    readonly fileName: string;
    readonly body: string;
    /** Overrides the view's own template for this one capture. */
    readonly template?: string;
    /**
     * Append into this existing note instead of creating one — under a heading when given, at the end
     * otherwise. For the captures that belong *inside* something: a daily log, a running inbox, a topic
     * note collecting everything on one subject.
     */
    readonly appendTo?: {
      readonly path: string;
      readonly heading?: string;
      readonly createHeading?: boolean;
    };
  };
  /**
   * Save as a note or a row, for this capture only.
   *
   * Without this, capturing a whole page was reachable only from a view someone had already configured as
   * note-shaped in Obsidian — so anyone whose views were all row-shaped had no way to keep an article at
   * all, which is the one thing every other clipper does.
   */
  readonly shape?: "row" | "note";
}

export interface CaptureResponse {
  readonly ok: boolean;
  /** Written, but something is worth knowing — e.g. the file written isn't one the view reads. */
  readonly warning?: string;
  /** How many rows were written, when several were sent. */
  readonly written?: number;
  readonly path?: string;
  readonly createdTable?: boolean;
  readonly duplicate?: { readonly on: string; readonly filePath: string };
  readonly unmapped?: readonly string[];
  readonly reason?: string;
}

/** Protocol version. Bumped when a wire shape changes incompatibly, so the extension can refuse politely. */
export const BRIDGE_PROTOCOL = 1;

// ---- Search (the read path) ----

/**
 * How to search. Keyword is the BM25 index; semantic finds by meaning; ask returns the passages that answer
 * a question rather than a ranked list of documents.
 */
export type SearchMode = "keyword" | "semantic" | "ask";

export interface SearchRequest {
  readonly query: string;
  readonly mode?: SearchMode;
  readonly limit?: number;
}

export interface SearchHit {
  readonly id: string;
  readonly title: string;
  /** note | row | pdf | docx | xlsx | pptx | epub | image | link | zotero | zotero-annotation */
  readonly source: string;
  /** Vault-relative path, when the hit lives in a file. */
  readonly path?: string;
  /** Heading, page or section within the file. */
  readonly location?: string;
  readonly snippet?: string;
  /** For link and Zotero hits, where the thing actually is. */
  readonly url?: string;
  readonly score: number;
}

export interface SearchResponse {
  readonly mode: SearchMode;
  readonly hits: readonly SearchHit[];
  /** Set when search is available but the index hasn't finished, so the caller can say so. */
  readonly building?: boolean;
}

/**
 * The answer to `/ping`.
 *
 * Says only "a KVS bridge is here, speaking this protocol" — deliberately not the vault name, nor whether
 * anything is paired, nor what views exist. Enough for the companion to find the right port without
 * anyone having to type one, and nothing more than that.
 */
export interface PingResponse {
  readonly kvs: true;
  readonly protocol: number;
  /** The plugin's own version, so a companion can tell when the vault side is older than it is. */
  readonly pluginVersion?: string;
}

/** Ports the companion looks on. The first is the default; the rest cover a clash with something else. */
export const DISCOVERY_PORTS: readonly number[] = [27180, 27181, 27182, 27183, 27184];

/** Whether a response body is genuinely a KVS bridge saying hello. */
export function isBridgePing(body: unknown): body is PingResponse {
  if (body === null || typeof body !== "object") return false;
  const record = body as Record<string, unknown>;
  return record["kvs"] === true && typeof record["protocol"] === "number";
}

/** What the companion needs to connect, however the person supplied it. */
export interface PairingInput {
  readonly code: string;
  /** Present when the link carried one; otherwise the companion discovers it. */
  readonly port?: number;
}

/**
 * Read whatever was pasted into the pairing box.
 *
 * Accepts a bare six-digit code typed by hand, or a connection link copied from Obsidian in one click. The
 * link is the reason this exists: it carries the port alongside the code, so the two things people most
 * often get wrong arrive together and neither has to be typed.
 *
 * Deliberately forgiving about spacing and separators — a code read off a screen is frequently pasted with
 * a stray space, and refusing that would be pedantry rather than security. The code itself still has to be
 * exactly right, and is still single-use.
 */
export function parsePairingInput(raw: string): PairingInput | null {
  const text = raw.trim();
  if (text === "") return null;

  // A connection link: kvs://pair?port=27180&code=123456
  const link = /^kvs:\/\/pair\b(.*)$/i.exec(text);
  if (link) {
    const query = link[1] ?? "";
    const code = /[?&]code=([0-9]{4,10})\b/.exec(query)?.[1];
    const port = /[?&]port=([0-9]{2,5})\b/.exec(query)?.[1];
    if (code === undefined) return null;
    const portNumber = port === undefined ? undefined : Number(port);
    const usable = portNumber !== undefined && portNumber >= 1024 && portNumber <= 65535;
    return { code, ...(usable ? { port: portNumber } : {}) };
  }

  // A bare code, possibly pasted with spaces or dashes between the digits.
  const digits = text.replace(/[\s-]/g, "");
  if (/^[0-9]{4,10}$/.test(digits)) return { code: digits };
  return null;
}

/** The link Obsidian offers to copy, carrying both things the companion needs. */
export function buildConnectionLink(port: number, code: string): string {
  return `kvs://pair?port=${String(port)}&code=${code}`;
}

// ---- Editing what you already have, and recognising it in search results ----

/**
 * Normalize a URL so the same page recognises itself.
 *
 * The link in a search result, the one in your address bar, and the one you saved last month are frequently
 * different strings for one page — campaign parameters, a fragment, a trailing slash, a capitalised host.
 * Comparing them raw means a page you definitely have looks new, which is precisely the moment recall is
 * supposed to help.
 *
 * Deliberately conservative: only parameters that are demonstrably tracking are dropped. A query string is
 * often load-bearing (`?id=`, `?q=`, `?v=`), and discarding one would merge two genuinely different pages.
 */
export function normalizeUrl(raw: string): string {
  const text = raw.trim();
  if (text === "") return "";
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return text.toLowerCase();
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return text.toLowerCase();

  const drop = /^(utm_|ga_|mc_|pk_|hsa_|vero_|_hs|icid$|igshid$|fbclid$|gclid$|dclid$|msclkid$|mkt_tok$|ref$|ref_src$|source$|spm$|scid$)/i;
  const keep = [...url.searchParams.entries()].filter(([key]) => !drop.test(key));
  url.search = "";
  for (const [key, value] of keep.sort(([a], [b]) => a.localeCompare(b))) url.searchParams.append(key, value);

  url.hash = "";
  url.hostname = url.hostname.toLowerCase().replace(/^www\./, "");
  url.protocol = "https:";
  const path = url.pathname.replace(/\/+$/, "");
  const query = url.search;
  return `${url.protocol}//${url.host}${path === "" ? "" : path}${query}`;
}

/** Ask whether any of these pages are already in the vault. */
export interface KnownRequest {
  readonly urls: readonly string[];
  readonly viewIds?: readonly string[];
}

/**
 * The answer: which of them are present, and nothing else.
 *
 * Only the URLs come back — not titles, not paths, not which view. This endpoint is answered to a script
 * running on a search results page, and that script has no business learning what your vault contains
 * beyond the question it asked.
 */
export interface KnownResponse {
  readonly known: readonly string[];
}

export interface UpdateRequest {
  readonly viewId: string;
  /** Opaque handle from a lookup result. Matched against the vault's own rows, never dereferenced. */
  readonly rowRef: string;
  /**
   * Each change either replaces the cell or appends to it. Appending is how an annotation joins the ones
   * already there: the cell keeps its history, `<br>`-separated, instead of each highlight erasing the last.
   */
  readonly values: readonly {
    readonly key: string;
    readonly value: string;
    readonly mode?: "set" | "append";
  }[];
}

export interface UpdateResponse {
  readonly ok: boolean;
  readonly updated?: readonly string[];
  /** Columns that were asked for but couldn't be written, and why — rather than failing silently. */
  readonly skipped?: readonly { readonly column: string; readonly reason: string }[];
  readonly reason?: string;
}

// ---- Reading a view, for showing a dashboard outside Obsidian ----

/** One row as the companion sees it. */
export interface RowsRow {
  /** Opaque handle, so a row shown here can be edited without ever naming a path. */
  readonly rowRef: string;
  readonly cells: Readonly<Record<string, string>>;
  /** Columns this row doesn't own — computed, or belonging to another source. */
  readonly readOnly?: readonly string[];
}

export interface RowsRequest {
  readonly viewId: string;
  readonly page?: number;
  readonly pageSize?: number;
  /** Narrow to rows matching this text, using the view's own search. */
  readonly query?: string;
  /** Narrow to rows about one page, for showing what you've already noted about it. */
  readonly url?: string;
}

export interface RowsResponse {
  readonly ok: boolean;
  readonly columns: readonly SchemaColumn[];
  readonly rows: readonly RowsRow[];
  readonly total: number;
  readonly page: number;
  readonly pageSize: number;
  readonly reason?: string;
}

// ---- Annotations: a highlight, and enough context to find it again ----

/**
 * Where a highlight sits in a page.
 *
 * Positions are useless for this — a page rerenders, an advert loads, a paragraph is edited, and an offset
 * points at something else entirely. Quoting the text with a little of what surrounds it survives all of
 * that, and when the passage really has gone, failing to find it is the correct outcome rather than
 * silently highlighting the wrong sentence. This is the selector model the W3C annotation spec settled on
 * for the same reasons.
 */
export interface TextAnchor {
  readonly exact: string;
  readonly prefix?: string;
  readonly suffix?: string;
}

export interface Annotation {
  readonly url: string;
  readonly anchor: TextAnchor;
  readonly note?: string;
  readonly createdAt: string;
}

export interface PromoteRequest {
  readonly viewId: string;
  readonly rowRef: string;
}

export interface PromoteResponse {
  readonly ok: boolean;
  readonly path?: string;
  /** False when the note already existed and was found rather than made. */
  readonly created?: boolean;
  readonly reason?: string;
}

// ---- Web annotations ----

/** One highlight, as the wire carries it. */
export interface WireAnnotation {
  readonly id: string;
  readonly anchor: TextAnchor;
  readonly color: string;
  /** "highlight" (painted over) or "underline" (drawn beneath). Anything else reads as highlight. */
  readonly style?: string;
  readonly note?: string;
  readonly createdAt: string;
}

export interface AnnotateRequest {
  readonly viewId: string;
  readonly url: string;
  readonly annotation: WireAnnotation;
  /** Page metadata for creating the row when the page has none yet — a highlight needs a row to land in. */
  readonly fields?: readonly { readonly key: string; readonly value: string }[];
}

export interface AnnotateResponse {
  readonly ok: boolean;
  /** The row the annotation landed in, freshly created or found. */
  readonly rowRef?: string;
  readonly createdRow?: boolean;
  /** Whether the human-readable copies were written. */
  readonly wroteCell?: boolean;
  readonly wroteNote?: boolean;
  readonly reason?: string;
}

export interface AnnotationsRequest {
  readonly url: string;
}

export interface AnnotationsResponse {
  readonly ok: boolean;
  readonly annotations: readonly WireAnnotation[];
}

export interface AnnotationRemoveRequest {
  readonly url: string;
  readonly id: string;
  /** The view whose row should lose the matching cell line, when known. */
  readonly viewId?: string;
}

export interface AnnotationRemoveResponse {
  readonly ok: boolean;
  readonly removedFromCell?: boolean;
}
