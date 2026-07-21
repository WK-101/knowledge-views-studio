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
  executeScript?(injection: { target: { tabId: number }; files: string[] }): Promise<unknown>;
}

interface TabsApi {
  query(info: { url: string[] }): Promise<{ id?: number }[]>;
}
function tabs(): TabsApi | null {
  const g = globalThis as unknown as { browser?: { tabs?: TabsApi }; chrome?: { tabs?: TabsApi } };
  return g.browser?.tabs ?? g.chrome?.tabs ?? null;
}

/**
 * Inject the annotator into tabs that are already open.
 *
 * Registration only affects future navigations, so a tab open when the feature was switched on — or when
 * the browser started — never received the script, and its saved highlights never repainted. This reaches
 * those tabs once; the content script's own idempotency marker keeps a second injection harmless.
 */
export async function injectAnnotatorIntoOpenTabs(): Promise<void> {
  const scriptApi = scripting();
  const tabApi = tabs();
  if (scriptApi?.executeScript === undefined || tabApi === null) return;
  try {
    const open = await tabApi.query({ url: ["http://*/*", "https://*/*"] });
    for (const tab of open) {
      if (tab.id === undefined) continue;
      try {
        await scriptApi.executeScript({ target: { tabId: tab.id }, files: ["annotate.js"] });
      } catch {
        // Some tabs refuse injection (a page that navigated away, a restricted URL); skip and continue.
      }
    }
  } catch {
    // No tabs permission or none open — nothing to do.
  }
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
