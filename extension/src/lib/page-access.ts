/**
 * Permission to read the page you're looking at.
 *
 * The popup gets this free: clicking the toolbar button grants `activeTab` for that tab, for that moment.
 * A sidebar never does. It isn't opened by clicking the action, so no gesture ever attaches `activeTab` to
 * anything, and every attempt to read the page fails — which is why the sidebar reported that it couldn't
 * capture a page the popup handled seconds earlier.
 *
 * There is no clever way around that; it is the permission model working as intended. A surface that stays
 * open and reads whatever you navigate to genuinely is asking for more than one that reads a single page
 * when you click. So it asks, once, and only when the sidebar is actually used.
 */

const ALL_PAGES = ["http://*/*", "https://*/*"];

interface PermissionsApi {
  request(p: { origins: string[] }): Promise<boolean>;
  contains(p: { origins: string[] }): Promise<boolean>;
}

function permissions(): PermissionsApi | null {
  const g = globalThis as unknown as {
    browser?: { permissions?: PermissionsApi };
    chrome?: { permissions?: PermissionsApi };
  };
  return g.browser?.permissions ?? g.chrome?.permissions ?? null;
}

/** Whether pages can be read without a fresh toolbar click. */
export async function hasPageAccess(): Promise<boolean> {
  const api = permissions();
  if (api === null) return false;
  try {
    return await api.contains({ origins: ALL_PAGES });
  } catch {
    return false;
  }
}

/**
 * Ask for it.
 *
 * Not async before the call, for the same reason the search-host request isn't: a prompt is only shown while
 * the browser can still see the click behind it.
 */
export function requestPageAccess(): Promise<boolean> {
  const api = permissions();
  if (api === null) return Promise.resolve(false);
  try {
    return api.request({ origins: ALL_PAGES }).catch(() => false);
  } catch {
    return Promise.resolve(false);
  }
}

// ---- The annotator's registration --------------------------------------

const ANNOTATOR_ID = "kvs-annotator";

interface ScriptingApi {
  registerContentScripts?(scripts: object[]): Promise<void>;
  unregisterContentScripts?(filter: { ids: string[] }): Promise<void>;
  getRegisteredContentScripts?(filter: { ids: string[] }): Promise<object[]>;
}

function scripting(): ScriptingApi | null {
  const g = globalThis as unknown as {
    browser?: { scripting?: ScriptingApi };
    chrome?: { scripting?: ScriptingApi };
  };
  return g.browser?.scripting ?? g.chrome?.scripting ?? null;
}

/**
 * Run the annotator on every page.
 *
 * It has to be registered, not injected on demand: restoring highlights when a page opens only works if the
 * script is already there when the page opens. Registration survives only alongside the page-access
 * permission — refuse that, and this quietly stays off.
 */
export async function registerAnnotator(): Promise<void> {
  const api = scripting();
  if (api?.registerContentScripts === undefined) return;
  try {
    const existing = (await api.getRegisteredContentScripts?.({ ids: [ANNOTATOR_ID] })) ?? [];
    if (existing.length > 0) return;
    await api.registerContentScripts([
      { id: ANNOTATOR_ID, matches: ["http://*/*", "https://*/*"], js: ["annotate.js"], runAt: "document_idle" },
    ]);
  } catch {
    // Without the permission, registration fails and the feature simply stays off.
  }
}

export async function unregisterAnnotator(): Promise<void> {
  const api = scripting();
  try {
    await api?.unregisterContentScripts?.({ ids: [ANNOTATOR_ID] });
  } catch {
    // Nothing registered is the state we wanted.
  }
}
