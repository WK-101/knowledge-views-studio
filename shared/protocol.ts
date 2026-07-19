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
}

export interface CaptureResponse {
  readonly ok: boolean;
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
