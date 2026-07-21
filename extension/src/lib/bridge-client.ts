import type {
  CaptureRequest,
  CaptureResponse,
  LookupMatch,
  SchemaResponse,
  SearchRequest,
  SearchResponse,
  UpdateRequest,
  UpdateResponse,
  KnownResponse,
  RowsRequest,
  RowsResponse,
  PromoteRequest,
  PromoteResponse,
  AnnotateRequest,
  AnnotateResponse,
  AnnotationsRequest,
  AnnotationsResponse,
  AnnotationRemoveRequest,
  AnnotationRemoveResponse,
} from "../../../shared/protocol";
import { DISCOVERY_PORTS, isBridgePing } from "../../../shared/protocol";

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
    // A 404 from a paired vault means the endpoint doesn't exist there — i.e. the plugin predates this
    // companion. "Not found" reads as a bug; the truthful sentence is "update the plugin".
    const message =
      response.status === 404
        ? "Your vault's plugin doesn't have this feature yet — update Knowledge Views Studio in Obsidian, then reload it."
        : ((body as { error?: string }).error ?? `Request failed (${String(response.status)}).`);
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

/**
 * Find the vault without anyone having to type a port.
 *
 * Tries the default first and then a short list of neighbours, stopping at the first bridge that answers.
 * Each attempt is given a short deadline, because a closed port on loopback fails instantly while a
 * *filtered* one can hang — and a setup screen that appears frozen is worse than one that says it found
 * nothing.
 */
export async function discoverBridge(ports: readonly number[] = DISCOVERY_PORTS): Promise<string | null> {
  for (const port of ports) {
    const baseUrl = `http://127.0.0.1:${String(port)}`;
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 700);
      const response = await fetch(`${baseUrl}/ping`, { signal: controller.signal });
      clearTimeout(timer);
      if (!response.ok) continue;
      if (isBridgePing(await response.json())) return baseUrl;
    } catch {
      // Nothing listening, or not ours. Try the next.
    }
  }
  return null;
}

/** Whether a bridge is reachable at a known address — used by the setup screen's live status. */
export async function bridgeReachable(baseUrl: string): Promise<boolean> {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 700);
    const response = await fetch(`${baseUrl}/ping`, { signal: controller.signal });
    clearTimeout(timer);
    return response.ok && isBridgePing(await response.json());
  } catch {
    return false;
  }
}

export async function update(connection: Connection, request: UpdateRequest): Promise<UpdateResponse> {
  return call<UpdateResponse>(connection, "/update", { method: "POST", body: JSON.stringify(request) });
}

/** Ask which of these pages are already saved. Answers with urls only — nothing about what's in the vault. */
export async function known(connection: Connection, urls: readonly string[]): Promise<KnownResponse> {
  return call<KnownResponse>(connection, "/known", { method: "POST", body: JSON.stringify({ urls }) });
}

/** Read a view's rows, for showing a dashboard or what's already noted about a page. */
export async function rows(connection: Connection, request: RowsRequest): Promise<RowsResponse> {
  return call<RowsResponse>(connection, "/rows", { method: "POST", body: JSON.stringify(request) });
}

/** Create — or find — a row's dedicated note. Idempotent on the vault side. */
export async function promote(connection: Connection, request: PromoteRequest): Promise<PromoteResponse> {
  return call<PromoteResponse>(connection, "/promote", { method: "POST", body: JSON.stringify(request) });
}

/** Save a highlight: sidecar, row cell, and dedicated note in one call. */
export async function annotate(connection: Connection, request: AnnotateRequest): Promise<AnnotateResponse> {
  return call<AnnotateResponse>(connection, "/annotate", { method: "POST", body: JSON.stringify(request) });
}

/** Everything saved for a page, for repainting. */
export async function annotationsFor(
  connection: Connection,
  request: AnnotationsRequest,
): Promise<AnnotationsResponse> {
  return call<AnnotationsResponse>(connection, "/annotations", { method: "POST", body: JSON.stringify(request) });
}

/** Delete a highlight, cleaning up its row line where possible. */
export async function annotateRemove(
  connection: Connection,
  request: AnnotationRemoveRequest,
): Promise<AnnotationRemoveResponse> {
  return call<AnnotationRemoveResponse>(connection, "/annotate/remove", {
    method: "POST",
    body: JSON.stringify(request),
  });
}
