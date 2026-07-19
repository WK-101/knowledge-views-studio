import { secretsMatch } from "./auth";
import type { BridgePermission, BridgeRequest, BridgeSettings } from "./types";

/**
 * Deciding whether a request is allowed.
 *
 * Split out from the router and the server so the rules can be read in one place and tested without either.
 * Order matters and is deliberate: the cheapest, least revealing checks run first, so an unpaired or
 * disallowed caller learns as little as possible about what exists behind the bridge.
 */

export interface Denial {
  readonly status: number;
  readonly reason: string;
}

/**
 * Whether an origin may call at all.
 *
 * An empty allowlist means "any origin", which sounds lax but isn't the real gate — pairing is. It exists so
 * the bridge is usable before the extension's final ID is known, and can be tightened to exactly that ID
 * afterwards. Requests with no Origin header at all (curl, a script) are treated as same-machine callers and
 * left to the token check.
 */
/** Whether an origin is a browser extension rather than an ordinary web page. */
export function isExtensionOrigin(origin: string): boolean {
  return /^(chrome-extension|moz-extension|safari-web-extension|extension):\/\//i.test(origin.trim());
}

/**
 * Whether an origin may call at all.
 *
 * An empty allowlist means "any extension", not "anything". That distinction matters: a page you happen to
 * be visiting can issue requests to 127.0.0.1, so permitting every origin would let any website discover
 * that you run this plugin — and probe the endpoints, even if the token then turned it away. Ordinary web
 * origins are therefore refused unless deliberately listed, while requests carrying no Origin at all (a
 * script, curl) are treated as same-machine callers and left to the token check.
 *
 * A non-empty allowlist is taken literally, so anyone with a reason to permit a web origin still can.
 */
export function originAllowed(origin: string | undefined, allowed: readonly string[]): boolean {
  if (allowed.length > 0) {
    if (origin === undefined || origin === "") return true;
    return allowed.some((a) => a.trim() !== "" && a.trim().toLowerCase() === origin.trim().toLowerCase());
  }
  if (origin === undefined || origin === "") return true;
  return isExtensionOrigin(origin);
}

/** Whether a view may be seen or written through the bridge. */
export function isViewExposed(viewId: string, settings: BridgeSettings): boolean {
  const list = settings.exposedViewIds;
  if (list === null) return true;
  return list.includes(viewId);
}

/**
 * Check a request against the settings for the permission a route requires.
 *
 * Returns null when the request may proceed, or the denial to send back. Read and write are checked
 * separately so someone can grant search-from-the-browser without also granting the ability to write.
 */
export function checkAccess(
  request: BridgeRequest,
  permission: BridgePermission,
  settings: BridgeSettings,
): Denial | null {
  if (!settings.enabled) return { status: 503, reason: "The browser bridge is turned off." };

  if (!originAllowed(request.origin, settings.allowedOrigins)) {
    return { status: 403, reason: "This origin is not allowed to use the bridge." };
  }

  // Pairing is public by necessity — it's how a caller gets a token in the first place.
  if (permission === "public") return null;

  if (permission === "read" && !settings.allowRead) {
    return { status: 403, reason: "Reading through the bridge is turned off." };
  }
  if (permission === "write" && !settings.allowWrite) {
    return { status: 403, reason: "Writing through the bridge is turned off." };
  }
  if (permission === "search" && !settings.allowSearch) {
    return { status: 403, reason: "Searching through the bridge is turned off." };
  }

  if (settings.token === null) {
    return { status: 401, reason: "Nothing is paired with this vault yet." };
  }
  if (!secretsMatch(settings.token, request.token)) {
    return { status: 401, reason: "That token is not valid for this vault." };
  }
  return null;
}

/** CORS headers for a browser caller. Echoes a permitted origin rather than using a wildcard. */
export function corsHeaders(origin: string | undefined, settings: BridgeSettings): Record<string, string> {
  const headers: Record<string, string> = {
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
  if (origin !== undefined && origin !== "" && originAllowed(origin, settings.allowedOrigins)) {
    headers["Access-Control-Allow-Origin"] = origin;
  }
  return headers;
}
