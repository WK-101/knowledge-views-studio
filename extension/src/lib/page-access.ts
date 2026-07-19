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
