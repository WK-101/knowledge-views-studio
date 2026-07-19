import { SEARCH_HOSTS } from "../../../shared/serp";

/**
 * Turning the search-page marks on and off.
 *
 * The hosts are requested **together, at the moment the feature is enabled** — never at install. Declaring
 * them in the manifest would put "Google, Bing, DuckDuckGo, PubMed…" in the install prompt, which is a
 * terrible first impression for a tool arguing that nothing leaves your machine, and would ask for access
 * from people who never turn the feature on.
 *
 * It is still one decision rather than a site-by-site interrogation: all of them, once, when you ask for it.
 */

export const SEARCH_ORIGINS: readonly string[] = SEARCH_HOSTS.map((host) => `https://${host}/*`);

const SCRIPT_ID = "kvs-serp";

interface PermissionsApi {
  request(p: { origins: string[] }): Promise<boolean>;
  contains(p: { origins: string[] }): Promise<boolean>;
  remove?(p: { origins: string[] }): Promise<boolean>;
}
interface ScriptingApi {
  registerContentScripts?(scripts: unknown[]): Promise<void>;
  unregisterContentScripts?(filter: { ids: string[] }): Promise<void>;
  getRegisteredContentScripts?(filter?: { ids: string[] }): Promise<unknown[]>;
}

function permissions(): PermissionsApi | null {
  const g = globalThis as unknown as {
    browser?: { permissions?: PermissionsApi };
    chrome?: { permissions?: PermissionsApi };
  };
  return g.browser?.permissions ?? g.chrome?.permissions ?? null;
}
function scripting(): ScriptingApi | null {
  const g = globalThis as unknown as {
    browser?: { scripting?: ScriptingApi };
    chrome?: { scripting?: ScriptingApi };
  };
  return g.browser?.scripting ?? g.chrome?.scripting ?? null;
}

export async function hasSearchAccess(): Promise<boolean> {
  const api = permissions();
  if (api === null) return false;
  try {
    return await api.contains({ origins: [...SEARCH_ORIGINS] });
  } catch {
    return false;
  }
}

/**
 * Ask for every search host in one prompt.
 *
 * Deliberately **not** async up to the point of asking: the call to `request` has to happen on the same tick
 * as the click that prompted it, because a browser will only show a permission prompt while it can still
 * attribute one to a user action. Returning the promise rather than awaiting inside keeps that true for
 * callers too.
 */
export function requestSearchAccess(): Promise<boolean> {
  const api = permissions();
  if (api === null) return Promise.resolve(false);
  try {
    return api.request({ origins: [...SEARCH_ORIGINS] }).catch(() => false);
  } catch {
    return Promise.resolve(false);
  }
}

/** Register the marker so it runs on search pages. Safe to call when it is already registered. */
export async function registerSearchScript(): Promise<void> {
  const api = scripting();
  if (api?.registerContentScripts === undefined) return;
  try {
    const existing = (await api.getRegisteredContentScripts?.({ ids: [SCRIPT_ID] })) ?? [];
    if (existing.length > 0) return;
    await api.registerContentScripts([
      { id: SCRIPT_ID, matches: [...SEARCH_ORIGINS], js: ["serp.js"], runAt: "document_idle" },
    ]);
  } catch {
    // Registration can fail if the permission was refused; the feature simply stays off.
  }
}

export async function unregisterSearchScript(): Promise<void> {
  const api = scripting();
  if (api?.unregisterContentScripts === undefined) return;
  try {
    await api.unregisterContentScripts({ ids: [SCRIPT_ID] });
  } catch {
    // Nothing registered; nothing to undo.
  }
}
