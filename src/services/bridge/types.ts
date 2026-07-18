/**
 * The local bridge: a small HTTP surface the browser companion talks to.
 *
 * Everything here is deliberately data, not behaviour. The request and response shapes are the contract the
 * extension will be written against, so they live apart from any server code and can be shared verbatim
 * later without dragging Node or Obsidian along.
 *
 * Two principles run through the design:
 *
 * **Nothing is on by default.** The bridge opens a port on the machine, which is a real thing to ask of
 * someone. It stays entirely inert until switched on, and even then reading and writing are separate
 * permissions — wanting your vault searchable from the browser shouldn't require granting the right to
 * write to it.
 *
 * **Nothing is fixed.** Port, permissions, which views are visible, payload limits and logging are all
 * settings rather than constants, because a shared laptop, a locked-down work machine and a personal
 * desktop want genuinely different answers.
 */

/** What a route needs before it will run. */
export type BridgePermission = "public" | "read" | "write";

export interface BridgeSettings {
  /** Master switch. Off means no port is opened at all. */
  readonly enabled: boolean;
  readonly port: number;
  /** Allow /schema and /lookup. */
  readonly allowRead: boolean;
  /** Allow /capture. Separate from reading on purpose. */
  readonly allowWrite: boolean;
  /**
   * Which views the bridge may see. `null` means all of them; a list narrows it, so a vault can expose one
   * reading list without exposing everything else in it.
   */
  readonly exposedViewIds: readonly string[] | null;
  /** Extension origins permitted to call. Empty means "any origin", which pairing still gates. */
  readonly allowedOrigins: readonly string[];
  /** Issued at pairing. Null means nothing is paired yet and only /pair will answer. */
  readonly token: string | null;
  /** Reject bodies larger than this, so a malformed or hostile request can't exhaust memory. */
  readonly maxBodyBytes: number;
  /** Keep a visible record of what the bridge was asked to do. */
  readonly logRequests: boolean;
}

export const DEFAULT_BRIDGE_SETTINGS: BridgeSettings = {
  enabled: false,
  port: 27180,
  allowRead: true,
  allowWrite: true,
  exposedViewIds: null,
  allowedOrigins: [],
  token: null,
  maxBodyBytes: 1_000_000,
  logRequests: true,
};

export interface BridgeRequest {
  readonly method: string;
  readonly path: string;
  readonly origin?: string | undefined;
  /** Bearer token from the Authorization header. */
  readonly token?: string | undefined;
  readonly body?: unknown;
}

export interface BridgeResponse {
  readonly status: number;
  readonly body: unknown;
}

/** One entry in the bridge's activity record. */
export interface BridgeLogEntry {
  readonly at: number;
  readonly method: string;
  readonly path: string;
  readonly status: number;
  readonly note?: string;
}

// ---- Wire shapes (the contract the extension is written against) ----

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
}

export interface CaptureResponse {
  readonly ok: boolean;
  readonly path?: string;
  readonly createdTable?: boolean;
  readonly duplicate?: { readonly on: string; readonly filePath: string };
  readonly unmapped?: readonly string[];
  readonly reason?: string;
}

/** Protocol version. Bumped when a wire shape changes incompatibly, so the extension can refuse politely. */
export const BRIDGE_PROTOCOL = 1;
