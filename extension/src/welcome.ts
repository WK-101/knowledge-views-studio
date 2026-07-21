import { fetchSchema, loadConnection } from "./lib/bridge-client";
import { loadPreferences, savePreferences } from "./lib/preferences";
import {
  hasPageAccess,
  injectAnnotatorIntoOpenTabs,
  registerAnnotator,
  requestPageAccess,
} from "./lib/page-access";

/**
 * The welcome / onboarding page.
 *
 * Opened once on install (and re-openable from settings). The bar is Web-Highlights-low: value first, no
 * account, no gate — three steps, each with a real action and a live done-state, so the page reflects what's
 * actually set up rather than telling the person to go and check. Pairing status is read live; highlighting
 * is turned on from here (permission asked from the click, as everywhere else); the third step is orientation.
 */

interface BrowserLike {
  runtime: { openOptionsPage(): void };
}
function browserApi(): BrowserLike | null {
  const g = globalThis as unknown as { browser?: BrowserLike; chrome?: BrowserLike };
  return g.browser ?? g.chrome ?? null;
}

const byId = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

/** Mark a step done: a check, a muted body, and a struck heading, so progress reads at a glance. */
function markStep(stepId: string, done: boolean): void {
  const step = document.getElementById(stepId);
  if (step === null) return;
  step.classList.toggle("wc-done", done);
}

/** Read pairing state and reflect it in step 1: connected (with the vault name), or not yet. */
async function refreshPairing(): Promise<void> {
  const status = byId("pairStatus");
  try {
    const connection = await loadConnection();
    if (connection.token === null) {
      status.textContent = "Not connected yet.";
      status.className = "wc-line";
      markStep("step-pair", false);
      return;
    }
    // A token isn't proof the vault is reachable now — confirm by asking for the schema, which also names it.
    try {
      const schema = await fetchSchema(connection);
      status.textContent = `Connected to “${schema.vault}”.`;
      status.className = "wc-line wc-ok";
      markStep("step-pair", true);
    } catch {
      status.textContent = "Paired, but Obsidian isn't reachable right now — open it, with the bridge on.";
      status.className = "wc-line";
      markStep("step-pair", false);
    }
  } catch {
    status.textContent = "Couldn't check the connection.";
    status.className = "wc-line";
    markStep("step-pair", false);
  }
}

/** Reflect whether highlighting is already on (the pref plus the page-read permission). */
async function refreshHighlighting(): Promise<void> {
  const prefs = await loadPreferences();
  const on = prefs.annotations && (await hasPageAccess());
  const status = byId("hlStatus");
  status.hidden = !on;
  if (on) {
    status.textContent = "Highlighting is on — select text on any page to try it.";
    status.className = "wc-line wc-ok";
  }
  markStep("step-highlight", on);
  byId<HTMLButtonElement>("enableHl").textContent = on ? "Highlighting is on ✓" : "Enable highlighting";
}

function wire(): void {
  byId("openPair").addEventListener("click", () => browserApi()?.runtime.openOptionsPage());
  byId("openSettings").addEventListener("click", () => browserApi()?.runtime.openOptionsPage());
  byId("done").addEventListener("click", () => window.close());

  byId("enableHl").addEventListener("click", () => {
    // Permission asked straight from the gesture, before any await — the rule the rest of the extension
    // learned the hard way (a request that isn't tied to a real click is silently refused).
    const pending = requestPageAccess();
    void (async () => {
      const granted = (await pending) || (await hasPageAccess());
      if (!granted) {
        const status = byId("hlStatus");
        status.hidden = false;
        status.textContent = "Highlighting needs permission to read pages; nothing was changed.";
        status.className = "wc-line wc-err";
        return;
      }
      await savePreferences({ annotations: true });
      await registerAnnotator();
      await injectAnnotatorIntoOpenTabs();
      await refreshHighlighting();
    })();
  });

  // Coming back from the settings tab (after pairing) should update the page without a reload.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      void refreshPairing();
      void refreshHighlighting();
    }
  });
}

document.addEventListener("DOMContentLoaded", () => {
  wire();
  void refreshPairing();
  void refreshHighlighting();
});
