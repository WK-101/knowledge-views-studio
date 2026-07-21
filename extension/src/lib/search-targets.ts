import type { SearchHit, SearchMode } from "../../../shared/protocol";
import { obsidianLink } from "./bridge-client";

/**
 * Where "search the selection" can send a selection — the vault, and a configurable set of web engines.
 *
 * Same discipline as island-actions.ts: a catalogue of built-in engines (the single source of truth for
 * their names and URL templates), one stored config of `{ id, enabled }` choices, and one normalizer that
 * reconciles whatever is in storage against the catalogue — so a new built-in engine appears for existing
 * installs without a re-save, and a malformed stored entry can never break the menu. Custom engines are the
 * one addition: an entry carrying its own label and URL template, validated here so a bad template is
 * dropped rather than opening a junk tab.
 *
 * Everything in this module is pure — templates in, URLs out — so all of it is unit-testable without a
 * browser. The content script and the options page both read the same resolved list.
 */

/** A search engine the menu can offer: `%s` in the template is replaced by the encoded selection. */
export interface WebEngine {
  readonly id: string;
  readonly label: string;
  readonly template: string;
}

/**
 * The built-in engines. `defaultOn` is only the starting state — every engine is a toggle in settings.
 * Bing ships off: the default menu favours breadth (general, private, reference, academic) over listing
 * every general-purpose engine twice.
 */
export const WEB_ENGINES: readonly (WebEngine & { readonly defaultOn: boolean })[] = [
  { id: "google", label: "Google", template: "https://www.google.com/search?q=%s", defaultOn: true },
  { id: "duckduckgo", label: "DuckDuckGo", template: "https://duckduckgo.com/?q=%s", defaultOn: true },
  { id: "wikipedia", label: "Wikipedia", template: "https://en.wikipedia.org/wiki/Special:Search?search=%s", defaultOn: true },
  { id: "scholar", label: "Google Scholar", template: "https://scholar.google.com/scholar?q=%s", defaultOn: true },
  { id: "bing", label: "Bing", template: "https://www.bing.com/search?q=%s", defaultOn: false },
];

/** The prefix that marks an engine as user-defined rather than from the catalogue. */
export const CUSTOM_ENGINE_PREFIX = "custom-";

/**
 * One engine choice as stored: a built-in carries just its id and on/off (label and template always come
 * from the catalogue, so a fixed template fixes itself on update); a custom engine carries its own.
 */
export interface EngineChoice {
  readonly id: string;
  readonly enabled: boolean;
  readonly label?: string;
  readonly template?: string;
}

/** The whole search-targets config: the vault toggle, plus the engine list in menu order. */
export interface SearchTargets {
  readonly vault: boolean;
  readonly engines: readonly EngineChoice[];
}

export const DEFAULT_SEARCH_TARGETS: SearchTargets = {
  vault: true,
  engines: WEB_ENGINES.map((e) => ({ id: e.id, enabled: e.defaultOn })),
};

/** A custom engine's template must be a web URL with a `%s` slot — anything else is dropped. */
export function isUsableTemplate(template: string): boolean {
  return /^https?:\/\//i.test(template.trim()) && template.includes("%s");
}

const BUILT_IN = new Map(WEB_ENGINES.map((e) => [e.id, e]));

/**
 * Resolve a stored choice to something the menu can render: built-ins take the catalogue's label and
 * template, custom entries their own (already validated by the normalizer). Null for anything unusable.
 */
export function resolveEngine(choice: EngineChoice): WebEngine | null {
  const builtIn = BUILT_IN.get(choice.id);
  if (builtIn !== undefined) return { id: builtIn.id, label: builtIn.label, template: builtIn.template };
  if (
    choice.id.startsWith(CUSTOM_ENGINE_PREFIX) &&
    typeof choice.label === "string" &&
    choice.label.trim() !== "" &&
    typeof choice.template === "string" &&
    isUsableTemplate(choice.template)
  ) {
    return { id: choice.id, label: choice.label.trim(), template: choice.template.trim() };
  }
  return null;
}

/**
 * Make a stored search-targets config whole and valid: keep the person's order, on/off, and custom engines
 * (dropping any whose label or template can't be used), drop unknown or duplicate ids, and append — at
 * their default state — any built-in engine the stored list has never seen.
 */
export function normalizeSearchTargets(raw: unknown): SearchTargets {
  const value = raw !== null && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const seen = new Set<string>();
  const engines: EngineChoice[] = [];
  if (Array.isArray(value["engines"])) {
    for (const item of value["engines"]) {
      if (item === null || typeof item !== "object") continue;
      const entry = item as Record<string, unknown>;
      const id = entry["id"];
      if (typeof id !== "string" || seen.has(id)) continue;
      const enabled = entry["enabled"] !== false;
      if (BUILT_IN.has(id)) {
        seen.add(id);
        engines.push({ id, enabled });
        continue;
      }
      const custom: EngineChoice = {
        id,
        enabled,
        ...(typeof entry["label"] === "string" ? { label: entry["label"] } : {}),
        ...(typeof entry["template"] === "string" ? { template: entry["template"] } : {}),
      };
      if (resolveEngine(custom) !== null) {
        seen.add(id);
        engines.push(custom);
      }
    }
  }
  for (const engine of WEB_ENGINES) {
    if (!seen.has(engine.id)) engines.push({ id: engine.id, enabled: engine.defaultOn });
  }
  return { vault: value["vault"] !== false, engines };
}

/** Fold whitespace runs (including newlines) to single spaces — a query is one line. */
function collapse(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/** The URL a template opens for a selection — every `%s` replaced by the encoded, collapsed text. */
export function searchUrl(template: string, text: string): string {
  return template.split("%s").join(encodeURIComponent(collapse(text)));
}

/** The wire search mode for a stored preference ("meaning" is the preference's name for semantic). */
export function wireSearchMode(prefMode: string): SearchMode {
  return prefMode === "meaning" || prefMode === "semantic" ? "semantic" : prefMode === "ask" ? "ask" : "keyword";
}

/** A vault search hit, reduced to what the in-page results list shows. */
export interface DisplayHit {
  readonly title: string;
  readonly source: string;
  /** Where clicking goes: the hit's own URL, an obsidian:// link, or "" when it has neither. */
  readonly href: string;
  readonly snippet: string;
  readonly location: string;
}

/**
 * Turn the bridge's hits into display rows. External hits (links, Zotero) open where they actually are;
 * vault files open through obsidian://, which needs the vault's name — a hit with neither stays unlinked
 * rather than linking to nowhere.
 */
export function displayHits(hits: readonly SearchHit[], vaultName: string): DisplayHit[] {
  return hits.map((hit) => ({
    title: hit.title,
    source: hit.source,
    href:
      hit.url !== undefined && hit.url !== ""
        ? hit.url
        : hit.path !== undefined && hit.path !== "" && vaultName !== ""
          ? obsidianLink(vaultName, hit.path)
          : "",
    snippet: hit.snippet ?? "",
    location: hit.location ?? "",
  }));
}
