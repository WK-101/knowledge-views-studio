/**
 * The selection toolbar — the "island" — as a configurable set of actions rather than a fixed row.
 *
 * Everything the toolbar can show is one entry in ISLAND_ACTIONS: an id the content script renders, and the
 * label + hint the settings screen shows. A person's choices — which actions appear, and in what order — live
 * in one ordered list of `{ id, enabled }`, so any action they don't use can be turned off and the rest
 * reordered to taste. Nothing about the toolbar is hardcoded at the render site; it iterates this config.
 *
 * Adding a new action later (copy-as-quote, search the selection, a sticky note…) is a single entry here plus
 * a renderer in the content script — existing installs pick it up automatically, on and last in the order,
 * via normalizeIslandActions, without anyone having to re-save their settings.
 */

/** The actions the toolbar can offer. New ones are appended; ids are stable and stored, so don't rename. */
export type IslandActionId = "colors" | "style" | "intensity" | "note" | "copy" | "search" | "sticky";

export interface IslandActionMeta {
  readonly id: IslandActionId;
  /** Shown in settings as the row's name. */
  readonly label: string;
  /** Shown in settings under the name, one line on what it does. */
  readonly hint: string;
}

/**
 * The catalogue, in the order a fresh install shows them — today's toolbar, left to right. This array is the
 * single source of truth for what exists and the default order; the settings list and the toolbar both read
 * it, so they can never drift apart.
 */
export const ISLAND_ACTIONS: readonly IslandActionMeta[] = [
  { id: "colors", label: "Highlight colours", hint: "The palette swatches — click one to highlight the selection." },
  { id: "style", label: "Style toggle", hint: "Switch between a highlight and an underline." },
  { id: "intensity", label: "Transparency toggle", hint: "Cycle the highlight through light, medium, and strong." },
  { id: "note", label: "Add a note", hint: "Highlight and attach a note and tags in one step." },
  { id: "copy", label: "Copy", hint: "Copy the selection as a quote, a blockquote, or a link to the page." },
  { id: "search", label: "Search", hint: "Search the selection in your vault, or on the web engines you choose." },
  { id: "sticky", label: "Sticky note", hint: "Pin a draggable markdown note to the page, seeded with the selection." },
];

/** One action's placement: whether it shows, and (by its position in the list) where. */
export interface IslandAction {
  readonly id: IslandActionId;
  readonly enabled: boolean;
}

const KNOWN_IDS = new Set<IslandActionId>(ISLAND_ACTIONS.map((a) => a.id));

/** Everything on, in catalogue order — the default an install starts from. */
export const DEFAULT_ISLAND_ACTIONS: readonly IslandAction[] = ISLAND_ACTIONS.map((a) => ({ id: a.id, enabled: true }));

/**
 * Make a stored island-actions list whole and valid: keep the person's order and on/off for every action that
 * still exists, drop anything unknown or duplicated, and append — on, in catalogue order — any action they've
 * never seen (one added in a newer version). The result always lists exactly the known actions, once each, so
 * the toolbar and the settings list can trust it without re-checking.
 */
export function normalizeIslandActions(raw: unknown): IslandAction[] {
  const seen = new Set<IslandActionId>();
  const out: IslandAction[] = [];
  if (Array.isArray(raw)) {
    for (const item of raw) {
      if (item === null || typeof item !== "object") continue;
      const id = (item as { id?: unknown }).id;
      if (typeof id !== "string" || !KNOWN_IDS.has(id as IslandActionId) || seen.has(id as IslandActionId)) continue;
      seen.add(id as IslandActionId);
      // Absent or non-false means on, so a partial entry defaults to showing rather than silently hiding.
      out.push({ id: id as IslandActionId, enabled: (item as { enabled?: unknown }).enabled !== false });
    }
  }
  for (const a of ISLAND_ACTIONS) {
    if (!seen.has(a.id)) out.push({ id: a.id, enabled: true });
  }
  return out;
}
