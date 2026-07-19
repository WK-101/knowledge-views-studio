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
export type BridgePermission = "public" | "read" | "write" | "search";

export interface BridgeSettings {
  /** Master switch. Off means no port is opened at all. */
  readonly enabled: boolean;
  readonly port: number;
  /** Allow /schema and /lookup. */
  readonly allowRead: boolean;
  /** Allow /capture. Separate from reading on purpose. */
  readonly allowWrite: boolean;
  /**
   * Allow /search. Kept apart from `allowRead` because it is a much larger grant: reading tells a caller
   * what your views are *shaped* like, whereas searching can return the text inside your notes. Someone may
   * reasonably want capture without handing over the contents of the vault.
   */
  readonly allowSearch: boolean;
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
  allowSearch: false,
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

// The wire shapes live in shared/ so the extension compiles against exactly the same definitions.
export * from "../../../shared/protocol";
