/**
 * The selection toolbar's appearance and behaviour — how it looks, and when it shows — as one small settings
 * object, alongside island-actions.ts's list of *which* controls it shows.
 *
 * Every field has a default that reproduces the toolbar's current behaviour, so an install that has never
 * touched these sees no change; each one is an independent knob a person can turn to fit how they work. Read
 * in one place (the content script), written in one place (the settings screen), coerced through one
 * normalizer, so a malformed stored value can never leave the toolbar in a broken state.
 */

/** Overall scale of the toolbar. */
export type IslandSize = "small" | "medium" | "large";
/** Colour scheme: follow the page's, or force one. */
export type IslandTheme = "auto" | "light" | "dark";
/** When the toolbar appears on a selection. */
export type IslandTrigger = "auto" | "hold-alt" | "off";

export interface IslandSettings {
  /** How large the toolbar is drawn. */
  readonly size: IslandSize;
  /** Follow the page's light/dark, or pin one. */
  readonly theme: IslandTheme;
  /** Show as soon as text is selected, only while Alt is held, or never (leaving existing highlights alone). */
  readonly trigger: IslandTrigger;
  /** Don't show until at least this many characters are selected — 0 means any selection. */
  readonly minChars: number;
  /** Dismiss the toolbar when the page scrolls (it's pinned in place, so it otherwise lingers). */
  readonly hideOnScroll: boolean;
  /** Show when selecting inside an editable field (a rich-text editor); off keeps it out of the way there. */
  readonly inEditable: boolean;
}

/** The defaults — exactly today's behaviour, so nothing changes until a person chooses to change it. */
export const DEFAULT_ISLAND_SETTINGS: IslandSettings = {
  size: "medium",
  theme: "auto",
  trigger: "auto",
  minChars: 0,
  hideOnScroll: false,
  inEditable: true,
};

/** The most characters the minimum can sensibly be — a guard, not a meaningful limit. */
const MAX_MIN_CHARS = 100;

const SIZES = new Set<IslandSize>(["small", "medium", "large"]);
const THEMES = new Set<IslandTheme>(["auto", "light", "dark"]);
const TRIGGERS = new Set<IslandTrigger>(["auto", "hold-alt", "off"]);

/** Coerce a stored settings blob into a whole, valid IslandSettings, field by field, defaulting anything off. */
export function normalizeIslandSettings(raw: unknown): IslandSettings {
  const d = DEFAULT_ISLAND_SETTINGS;
  if (raw === null || typeof raw !== "object") return d;
  const v = raw as Record<string, unknown>;

  const size = v["size"];
  const theme = v["theme"];
  const trigger = v["trigger"];
  const minRaw = v["minChars"];
  const min =
    typeof minRaw === "number" && Number.isFinite(minRaw)
      ? Math.max(0, Math.min(MAX_MIN_CHARS, Math.floor(minRaw)))
      : d.minChars;

  return {
    size: typeof size === "string" && SIZES.has(size as IslandSize) ? (size as IslandSize) : d.size,
    theme: typeof theme === "string" && THEMES.has(theme as IslandTheme) ? (theme as IslandTheme) : d.theme,
    trigger:
      typeof trigger === "string" && TRIGGERS.has(trigger as IslandTrigger) ? (trigger as IslandTrigger) : d.trigger,
    minChars: min,
    hideOnScroll: v["hideOnScroll"] === true,
    inEditable: v["inEditable"] !== false,
  };
}

/** The multiplier each size applies to the whole toolbar. */
export const ISLAND_SIZE_SCALE: Record<IslandSize, number> = {
  small: 0.85,
  medium: 1,
  large: 1.2,
};
