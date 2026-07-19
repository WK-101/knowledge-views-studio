import type { SearchHit, SearchMode } from "../../../shared/protocol";
import { BridgeError, loadConnection, obsidianLink, search } from "./bridge-client";
import { loadPreferences } from "./preferences";

/**
 * Searching the vault from the browser.
 *
 * The read half of the companion. Comparable extensions have to borrow a separate search plugin to do this
 * at all; here the index is already in the vault, so keyword, meaning-based and question-answering search
 * come from one place — and they reach rows, annotations, attachments and Zotero, not only note titles.
 *
 * Kept as a module the popup mounts, rather than folded into it, so the capture path stays readable and
 * this can move to a sidebar later without being untangled first.
 */

interface Elements {
  readonly host: HTMLElement;
  readonly vaultName: () => string;
  readonly setStatus: (message: string, kind?: "info" | "error" | "ok") => void;
}

const MODES: readonly { id: SearchMode; label: string; hint: string }[] = [
  { id: "keyword", label: "Keyword", hint: '"phrase", -exclude, tag:x' },
  { id: "semantic", label: "Meaning", hint: "finds notes that say it differently" },
  { id: "ask", label: "Ask", hint: "a question; the passages that answer it" },
];

function node<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (text !== undefined) el.textContent = text;
  return el;
}

/** A short label for where a hit came from, so a row and a PDF page don't look alike. */
function sourceLabel(hit: SearchHit): string {
  switch (hit.source) {
    case "note":
      return "Note";
    case "row":
      return "Row";
    case "pdf":
      return "PDF";
    case "zotero":
      return "Zotero";
    case "zotero-annotation":
      return "Zotero note";
    case "link":
      return "Link";
    case "image":
      return "Image";
    default:
      return hit.source;
  }
}

export function mountSearch(elements: Elements): void {
  const { host, vaultName, setStatus } = elements;
  host.replaceChildren();

  const bar = node("div", { class: "searchbar" });
  const input = node("input", { type: "search", id: "q", placeholder: "Search your vault…" });
  const modeSelect = node("select", { id: "mode" });
  for (const mode of MODES) modeSelect.appendChild(node("option", { value: mode.id }, mode.label));
  // Opens in whichever mode someone actually uses. Applied once, and never after they've touched it.
  let modeChosen = false;
  modeSelect.addEventListener("change", () => {
    modeChosen = true;
  });
  void loadPreferences().then((prefs) => {
    if (!modeChosen && MODES.some((m) => m.id === prefs.searchMode)) modeSelect.value = prefs.searchMode;
  });
  bar.append(input, modeSelect);
  host.appendChild(bar);

  const hint = node("p", { class: "hint" }, MODES[0]?.hint ?? "");
  host.appendChild(hint);
  modeSelect.addEventListener("change", () => {
    hint.textContent = MODES.find((m) => m.id === modeSelect.value)?.hint ?? "";
  });

  const results = node("div", { class: "results" });
  host.appendChild(results);

  let inFlight = 0;
  const run = async (): Promise<void> => {
    const query = input.value.trim();
    if (query === "") {
      results.replaceChildren();
      return;
    }
    const ticket = ++inFlight;
    setStatus("Searching…");
    try {
      const connection = await loadConnection();
      const response = await search(connection, { query, mode: modeSelect.value as SearchMode, limit: 20 });
      // A slower earlier search must not overwrite a newer one's results.
      if (ticket !== inFlight) return;
      render(response.hits);
      setStatus(response.hits.length === 0 ? "Nothing found." : `${String(response.hits.length)} result(s)`);
    } catch (error) {
      if (ticket !== inFlight) return;
      results.replaceChildren();
      const message =
        error instanceof BridgeError && error.status === 403
          ? "Searching isn't allowed yet — turn it on in Obsidian's Browser bridge settings."
          : error instanceof BridgeError
            ? error.message
            : "Couldn't search your vault.";
      setStatus(message, "error");
    }
  };

  const render = (hits: readonly SearchHit[]): void => {
    results.replaceChildren();
    const vault = vaultName();
    for (const hit of hits) {
      const item = node("div", { class: "result" });
      const head = node("div", { class: "result-head" });
      head.appendChild(node("span", { class: "badge" }, sourceLabel(hit)));

      // Zotero and saved links live outside the vault, so they open where they actually are.
      const href =
        hit.url !== undefined && hit.url !== ""
          ? hit.url
          : hit.path !== undefined && vault !== ""
            ? obsidianLink(vault, hit.path)
            : "";
      if (href !== "") {
        const link = node("a", { href, target: "_blank", rel: "noreferrer" }, hit.title);
        head.appendChild(link);
      } else {
        head.appendChild(node("span", {}, hit.title));
      }
      item.appendChild(head);

      if (hit.location !== undefined && hit.location !== "") {
        item.appendChild(node("div", { class: "hint" }, hit.location));
      }
      if (hit.snippet !== undefined && hit.snippet !== "") {
        item.appendChild(node("p", { class: "snippet" }, hit.snippet));
      }
      results.appendChild(item);
    }
  };

  let timer: number | undefined;
  input.addEventListener("input", () => {
    // Search as you type, but not on every keystroke: each one costs an index query in the vault.
    window.clearTimeout(timer);
    timer = window.setTimeout(() => void run(), 250);
  });
  input.addEventListener("keydown", (event) => {
    if ((event as KeyboardEvent).key === "Enter") {
      window.clearTimeout(timer);
      void run();
    }
  });
  input.focus();
}
