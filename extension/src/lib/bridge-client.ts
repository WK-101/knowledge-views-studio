import type {
  CaptureRequest,
  CaptureResponse,
  LookupMatch,
  SchemaResponse,
  SearchRequest,
  SearchResponse,
} from "../../../shared/protocol";

/**
 * Talking to the vault.
 *
 * Two things are worth knowing about this file. The browser namespace differs between Chrome (`chrome`, all
 * callbacks in MV2, promises in MV3) and Firefox (`browser`, promises throughout), so it's resolved once
 * here rather than at every call site. And the bridge is on loopback, which browsers treat as a secure
 * context — so plain HTTP is fine and there's no certificate to install, which is the single biggest
 * usability difference from other local-server integrations.
 */

interface StorageArea {
  get(keys: string[] | null): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
}
interface BrowserApi {
  readonly storage: { readonly local: StorageArea };
}

/** The extension namespace, whichever this browser calls it. */
export function api(): BrowserApi {
  const g = globalThis as unknown as { browser?: BrowserApi; chrome?: BrowserApi };
  const found = g.browser ?? g.chrome;
  if (!found) throw new Error("No extension API available.");
  return found;
}

export interface Connection {
  /** Where the vault's bridge is listening. */
  readonly baseUrl: string;
  readonly token: string | null;
}

export const DEFAULT_BASE_URL = "http://127.0.0.1:27180";

export async function loadConnection(): Promise<Connection> {
  const stored = await api().storage.local.get(["baseUrl", "token"]);
  return {
    baseUrl: typeof stored["baseUrl"] === "string" && stored["baseUrl"] !== "" ? stored["baseUrl"] : DEFAULT_BASE_URL,
    token: typeof stored["token"] === "string" && stored["token"] !== "" ? stored["token"] : null,
  };
}

export async function saveConnection(patch: Partial<Connection>): Promise<void> {
  const items: Record<string, unknown> = {};
  if (patch.baseUrl !== undefined) items["baseUrl"] = patch.baseUrl;
  if (patch.token !== undefined) items["token"] = patch.token;
  await api().storage.local.set(items);
}

export class BridgeError extends Error {
  constructor(
    message: string,
    /** True when the vault simply isn't reachable — the case worth queueing rather than reporting. */
    readonly offline: boolean,
    readonly status?: number,
  ) {
    super(message);
    this.name = "BridgeError";
  }
}

async function call<T>(connection: Connection, path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (connection.token !== null) headers["Authorization"] = `Bearer ${connection.token}`;

  let response: Response;
  try {
    response = await fetch(`${connection.baseUrl}${path}`, { ...init, headers });
  } catch {
    // A refused connection means Obsidian is closed or the bridge is off — recoverable, so it's flagged
    // as offline and the caller can queue instead of losing the capture.
    throw new BridgeError("Can't reach your vault. Is Obsidian open with the bridge turned on?", true);
  }

  const text = await response.text();
  let body: unknown = {};
  try {
    body = text === "" ? {} : JSON.parse(text);
  } catch {
    throw new BridgeError("The vault sent something unreadable.", false, response.status);
  }

  if (!response.ok) {
    const message = (body as { error?: string }).error ?? `Request failed (${String(response.status)}).`;
    throw new BridgeError(message, false, response.status);
  }
  return body as T;
}

export async function fetchSchema(connection: Connection): Promise<SchemaResponse> {
  return call<SchemaResponse>(connection, "/schema", { method: "GET" });
}

export async function lookup(
  connection: Connection,
  query: { url?: string; doi?: string },
): Promise<{ matches: LookupMatch[] }> {
  return call(connection, "/lookup", { method: "POST", body: JSON.stringify(query) });
}

export async function capture(connection: Connection, request: CaptureRequest): Promise<CaptureResponse> {
  return call<CaptureResponse>(connection, "/capture", { method: "POST", body: JSON.stringify(request) });
}

export async function pair(baseUrl: string, code: string): Promise<{ token: string; vault: string }> {
  return call<{ token: string; vault: string }>(
    { baseUrl, token: null },
    "/pair",
    { method: "POST", body: JSON.stringify({ code }) },
  );
}

export async function search(connection: Connection, request: SearchRequest): Promise<SearchResponse> {
  return call<SearchResponse>(connection, "/search", { method: "POST", body: JSON.stringify(request) });
}

/**
 * A link that opens something in Obsidian.
 *
 * The `obsidian://` scheme is the only way back into the app from a browser, and it needs the vault by name
 * — which is why `/schema` reports it. Everything is encoded, since note paths routinely contain spaces and
 * characters that would otherwise end the URL early.
 */
export function obsidianLink(vault: string, path: string): string {
  return `obsidian://open?vault=${encodeURIComponent(vault)}&file=${encodeURIComponent(path)}`;
}
