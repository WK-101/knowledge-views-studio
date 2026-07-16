/**
 * Better BibTeX (BBT) owns citation keys, and its key formula is user-configured — so any key we generate
 * ourselves would eventually disagree with BBT's. Worse, BBT's key isn't in the standard Zotero API unless
 * the user has *pinned* it; by default keys are dynamic and absent from `/items`. The reliable source is
 * BBT's own JSON-RPC endpoint, which exposes `item.citationkey`: given Zotero item keys, it returns each
 * item's exact citation key straight from BBT's KeyManager.
 *
 * We use this so a cite key filled from Zotero is byte-for-byte what BBT would emit — no drift, ever. If BBT
 * isn't installed or the endpoint doesn't answer, the caller falls back to the pinned key (if any) and
 * otherwise leaves the cite key empty; we never substitute a guess.
 *
 * Endpoint: http://<host>:<port>/better-bibtex/json-rpc (POST). Reference: BBT JSON-RPC docs, method
 * item.citationkey, whose implementation is
 *   keys[item.key] = KeyManager.any(_ => _.libraryID === item.libraryID && _.itemKey === item.key)?.citationKey
 */

export type JsonPoster = (url: string, body: unknown) => Promise<{ status: number; json?: unknown; reason?: string }>;

/**
 * Derive BBT's JSON-RPC URL from the Zotero local-API base. The API base looks like
 * "http://127.0.0.1:23119/api/users/0"; BBT lives at the same origin under /better-bibtex/json-rpc.
 */
export function bbtEndpointFromApiBase(apiBase: string): string {
  try {
    const u = new URL(apiBase);
    return `${u.protocol}//${u.host}/better-bibtex/json-rpc`;
  } catch {
    // Best-effort string fallback if the base isn't a full URL.
    const origin = apiBase.replace(/\/api\/.*$/, "").replace(/\/+$/, "");
    return `${origin}/better-bibtex/json-rpc`;
  }
}

/**
 * Fetch exact BBT citation keys for the given Zotero item keys. Returns a map of itemKey → citationKey for
 * whichever keys BBT knows; items with no BBT key (or on any failure) are simply absent from the map. Never
 * throws.
 */
export async function fetchBbtCiteKeys(endpoint: string, itemKeys: readonly string[], poster: JsonPoster): Promise<Map<string, string>> {
  const out = new Map<string, string>();
  const keys = itemKeys.filter((k) => k !== "");
  if (keys.length === 0) return out;
  try {
    const res = await poster(endpoint, { jsonrpc: "2.0", method: "item.citationkey", params: [keys], id: 1 });
    if (res.status < 200 || res.status >= 300) return out;
    const result = (res.json as { result?: unknown } | undefined)?.result;
    if (result && typeof result === "object") {
      for (const [itemKey, citekey] of Object.entries(result as Record<string, unknown>)) {
        if (typeof citekey === "string" && citekey.trim() !== "") out.set(itemKey, citekey.trim());
      }
    }
  } catch {
    return out;
  }
  return out;
}

/** Convenience: the exact BBT cite key for a single item, or "" if BBT doesn't have one / is unreachable. */
export async function fetchBbtCiteKey(endpoint: string, itemKey: string, poster: JsonPoster): Promise<string> {
  const map = await fetchBbtCiteKeys(endpoint, [itemKey], poster);
  return map.get(itemKey) ?? "";
}
