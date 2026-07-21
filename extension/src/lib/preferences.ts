import type { DomainRule } from "../../../shared/rules";

/**
 * Every preference in one place.
 *
 * Settings had accumulated as loose storage keys read wherever they happened to be needed, each with its own
 * idea of what the default was. That works until two places disagree about it, at which point a feature is
 * on in one half of the extension and off in the other, and the bug is nearly invisible.
 *
 * One shape, one set of defaults, one place to change them.
 */

export type PopupSize = "small" | "medium" | "large";
export type SelectionStyle = "plain" | "quote";

export interface Preferences {
  /** Which view a capture goes to when nothing more specific applies. */
  readonly defaultViewId: string;
  /** Return to whatever was used last, unless a rule says otherwise. */
  readonly rememberLastView: boolean;
  readonly lastViewId: string;
  /** Per-site rules. The most specific matching one wins. */
  readonly rules: readonly DomainRule[];
  readonly popupSize: PopupSize;
  /** Whether a captured note includes the article body by default. */
  readonly includeContent: boolean;
  /** How a selection is written when it becomes a note's body. */
  readonly selectionStyle: SelectionStyle;
  /** Added to every capture, whatever the site. */
  readonly alwaysTags: string;
  /** The mode the search panel opens in. */
  readonly searchMode: "keyword" | "meaning" | "ask";
  readonly recallBadge: boolean;
  readonly serpMarks: boolean;
  /** Whether the in-page highlighter runs. Needs page access, asked for when this is turned on. */
  readonly annotations: boolean;
  /** The view highlights land in. Empty = decided per site (rule, then default, then last used). */
  readonly annotationViewId: string;
  /** Whether the local Zotero is consulted (search) and offered as a destination (save). */
  readonly zotero: boolean;
  /** The Zotero collection saves go into. Empty = wherever Zotero's own selection sits. */
  readonly zoteroCollectionKey: string;
  /**
   * Per-view column choices, keyed by view id. Each optional: an unset column falls back to the sensible
   * guess, so this is a set of overrides, not a required configuration. `urlColumn` is which cell holds
   * the page's URL for matching; `annotationColumn` is where highlight text is written.
   */
  readonly viewColumns: Readonly<Record<string, { urlColumn?: string; annotationColumn?: string }>>;
  /** Write each highlight in the row cell as a bullet-point line rather than a plain line. */
  readonly annotationBullets: boolean;
  /** Show the in-page highlights sidebar (a floating, draggable panel listing the page's highlights). */
  readonly annotationSidebar: boolean;
  /** Offer a hover action on data tables to capture their rows or copy them, without opening the popup. */
  readonly tableCapture: boolean;
}

export const DEFAULT_PREFERENCES: Preferences = {
  defaultViewId: "",
  rememberLastView: true,
  lastViewId: "",
  rules: [],
  popupSize: "medium",
  includeContent: true,
  selectionStyle: "quote",
  alwaysTags: "",
  searchMode: "keyword",
  recallBadge: false,
  serpMarks: false,
  annotations: false,
  annotationViewId: "",
  zotero: false,
  zoteroCollectionKey: "",
  viewColumns: {},
  annotationBullets: false,
  annotationSidebar: false,
  tableCapture: false,
};

const KEY = "preferences";

/** Coerce the per-view column map, dropping anything malformed. */
function normalizeViewColumns(raw: unknown): Record<string, { urlColumn?: string; annotationColumn?: string }> {
  if (raw === null || typeof raw !== "object") return {};
  const out: Record<string, { urlColumn?: string; annotationColumn?: string }> = {};
  for (const [viewId, value] of Object.entries(raw as Record<string, unknown>)) {
    if (value === null || typeof value !== "object") continue;
    const entry = value as Record<string, unknown>;
    const url = typeof entry["urlColumn"] === "string" ? entry["urlColumn"] : undefined;
    const ann = typeof entry["annotationColumn"] === "string" ? entry["annotationColumn"] : undefined;
    if (url !== undefined || ann !== undefined) {
      out[viewId] = { ...(url !== undefined ? { urlColumn: url } : {}), ...(ann !== undefined ? { annotationColumn: ann } : {}) };
    }
  }
  return out;
}

interface StorageApi {
  local: {
    get(keys: string[] | null): Promise<Record<string, unknown>>;
    set(items: Record<string, unknown>): Promise<void>;
  };
}
function storage(): StorageApi | null {
  const g = globalThis as unknown as { browser?: { storage?: StorageApi }; chrome?: { storage?: StorageApi } };
  return g.browser?.storage ?? g.chrome?.storage ?? null;
}

/** Coerce whatever is in storage into a whole, valid set of preferences. */
export function normalizePreferences(raw: unknown): Preferences {
  if (raw === null || typeof raw !== "object") return DEFAULT_PREFERENCES;
  const value = raw as Record<string, unknown>;

  const size = value["popupSize"];
  const style = value["selectionStyle"];
  const mode = value["searchMode"];
  const rules = Array.isArray(value["rules"])
    ? (value["rules"] as unknown[]).filter(
        (r): r is DomainRule =>
          r !== null &&
          typeof r === "object" &&
          typeof (r as DomainRule).domain === "string" &&
          typeof (r as DomainRule).viewId === "string",
      )
    : DEFAULT_PREFERENCES.rules;

  return {
    defaultViewId: typeof value["defaultViewId"] === "string" ? value["defaultViewId"] : "",
    rememberLastView: value["rememberLastView"] !== false,
    lastViewId: typeof value["lastViewId"] === "string" ? value["lastViewId"] : "",
    rules,
    popupSize: size === "small" || size === "large" ? size : "medium",
    includeContent: value["includeContent"] !== false,
    selectionStyle: style === "plain" ? "plain" : "quote",
    alwaysTags: typeof value["alwaysTags"] === "string" ? value["alwaysTags"] : "",
    searchMode: mode === "meaning" || mode === "ask" ? mode : "keyword",
    recallBadge: value["recallBadge"] === true,
    serpMarks: value["serpMarks"] === true,
    annotations: value["annotations"] === true,
    annotationViewId: typeof value["annotationViewId"] === "string" ? value["annotationViewId"] : "",
    zotero: value["zotero"] === true,
    zoteroCollectionKey: typeof value["zoteroCollectionKey"] === "string" ? value["zoteroCollectionKey"] : "",
    viewColumns: normalizeViewColumns(value["viewColumns"]),
    annotationBullets: value["annotationBullets"] === true,
    annotationSidebar: value["annotationSidebar"] === true,
    tableCapture: value["tableCapture"] === true,
  };
}

export async function loadPreferences(): Promise<Preferences> {
  const api = storage();
  if (api === null) return DEFAULT_PREFERENCES;
  try {
    const stored = await api.local.get([KEY, "popupSize", "recallBadge", "serpMarks"]);
    const merged = {
      ...(stored[KEY] as Record<string, unknown> | undefined),
      // Settings that predate this module, so an existing install keeps what it chose.
      ...(stored["popupSize"] !== undefined ? { popupSize: stored["popupSize"] } : {}),
      ...(stored["recallBadge"] !== undefined ? { recallBadge: stored["recallBadge"] } : {}),
      ...(stored["serpMarks"] !== undefined ? { serpMarks: stored["serpMarks"] } : {}),
    };
    return normalizePreferences(merged);
  } catch {
    return DEFAULT_PREFERENCES;
  }
}

export async function savePreferences(patch: Partial<Preferences>): Promise<Preferences> {
  const api = storage();
  const current = await loadPreferences();
  const next = normalizePreferences({ ...current, ...patch });
  if (api !== null) {
    try {
      // The individually-keyed settings stay written where the older code expects to find them.
      await api.local.set({
        [KEY]: next,
        popupSize: next.popupSize,
        recallBadge: next.recallBadge,
        serpMarks: next.serpMarks,
      });
    } catch {
      // Nothing useful to do; the caller still gets the merged value for this session.
    }
  }
  return next;
}
