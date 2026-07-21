import { hostOf } from "../../../shared/rules";
import { HIGHLIGHT_COLORS, HIGHLIGHT_INTENSITIES, type HighlightColor, type HighlightIntensity, type HighlightStyle } from "../../../shared/annotations";
import type { CopyFormat } from "./copy-formats";

/**
 * Per-site auto-actions — a rule that makes a chosen thing happen automatically on a matching site, so a
 * research session on one site doesn't cost a toolbar click per selection.
 *
 * This is the per-site `DomainRule` idea (capture rules) turned on the annotator: the *matching* is the same
 * — by host, most-specific-wins, reusing `hostOf` — but the *effect* is a behaviour, not a capture target, so
 * it lives in its own type rather than overloading DomainRule (a capture rule and a highlight-on-select rule
 * have nothing to say to each other, and cramming them together would force every one to think about the
 * other). Nothing here is fixed: every effect is opt-in and independently parameterised, which is the whole
 * point of an "expert" per-site rule.
 *
 * Two moments a site rule can act on, either or both:
 *
 *  - **on selection** — when you select text, fire one action (highlight in a set colour/style, copy in a set
 *    format, drop a sticky note from the selection, or open the note editor) instead of waiting for a click;
 *  - **on page load** — when you open a matching page, open the highlights sidebar and/or show the sticky
 *    launcher, so the tools you use on that site are already there.
 */

/** What a site rule does the moment you select text. "none" leaves the normal toolbar behaviour alone. */
export type OnSelectKind = "none" | "highlight" | "copy" | "sticky" | "note";

export const ON_SELECT_KINDS: readonly { id: OnSelectKind; label: string }[] = [
  { id: "none", label: "Nothing (show the toolbar)" },
  { id: "highlight", label: "Highlight it" },
  { id: "copy", label: "Copy it" },
  { id: "sticky", label: "Make a sticky note" },
  { id: "note", label: "Open the note editor" },
];

const COPY_FORMATS: readonly CopyFormat[] = ["quote", "blockquote", "markdown-link"];

/** One per-site auto-action rule. */
export interface SiteAutoAction {
  /** A host, matched the way capture rules match — subdomains included, most-specific rule wins. */
  readonly domain: string;
  /** What happens when text is selected on this site. */
  readonly onSelect: OnSelectKind;
  /** For `highlight` and `sticky`: the colour used. */
  readonly color?: HighlightColor;
  /** For `highlight`: painted over, or underlined. */
  readonly style?: HighlightStyle;
  /** For `highlight`: the transparency. */
  readonly intensity?: HighlightIntensity;
  /** For `copy`: which format lands on the clipboard. */
  readonly copyFormat?: CopyFormat;
  /** Expert: also show the toolbar after the on-selection action fires (default: don't — it already acted). */
  readonly alsoShowToolbar?: boolean;
  /** On page load: open the in-page highlights sidebar. */
  readonly openSidebar?: boolean;
  /** On page load: show the sticky-note launcher. */
  readonly showStickyLauncher?: boolean;
}

export const DEFAULT_SITE_AUTO_ACTIONS: readonly SiteAutoAction[] = [];

function coerceKind(raw: unknown): OnSelectKind {
  return typeof raw === "string" && ON_SELECT_KINDS.some((k) => k.id === raw) ? (raw as OnSelectKind) : "none";
}

function coerceColor(raw: unknown): HighlightColor | undefined {
  return typeof raw === "string" && (HIGHLIGHT_COLORS as readonly string[]).includes(raw)
    ? (raw as HighlightColor)
    : undefined;
}

/** True when a rule actually does something — so the list never fills with rules that quietly never fire. */
export function ruleHasEffect(rule: SiteAutoAction): boolean {
  return rule.onSelect !== "none" || rule.openSidebar === true || rule.showStickyLauncher === true;
}

/**
 * Coerce one stored rule into a whole, valid one, or null if it can't do anything (no host, or no effect).
 * Colour/style/format are kept only when the on-selection kind uses them, so a rule can't carry a stale
 * parameter that means nothing.
 */
export function coerceSiteAutoAction(raw: unknown): SiteAutoAction | null {
  if (raw === null || typeof raw !== "object") return null;
  const entry = raw as Record<string, unknown>;
  const domain = typeof entry["domain"] === "string" ? entry["domain"] : "";
  if (hostOf(domain) === "") return null;
  const onSelect = coerceKind(entry["onSelect"]);

  const color = coerceColor(entry["color"]);
  const style: HighlightStyle = entry["style"] === "underline" ? "underline" : "highlight";
  const intensity: HighlightIntensity =
    typeof entry["intensity"] === "string" && (HIGHLIGHT_INTENSITIES as readonly string[]).includes(entry["intensity"])
      ? (entry["intensity"] as HighlightIntensity)
      : "medium";
  const copyFormat: CopyFormat =
    typeof entry["copyFormat"] === "string" && COPY_FORMATS.includes(entry["copyFormat"] as CopyFormat)
      ? (entry["copyFormat"] as CopyFormat)
      : "quote";

  const rule: SiteAutoAction = {
    domain,
    onSelect,
    // Only carry the parameters the chosen kind actually uses.
    ...(onSelect === "highlight" || onSelect === "sticky" ? { color: color ?? "yellow" } : {}),
    ...(onSelect === "highlight" ? { style, intensity } : {}),
    ...(onSelect === "copy" ? { copyFormat } : {}),
    ...(onSelect !== "none" && entry["alsoShowToolbar"] === true ? { alsoShowToolbar: true } : {}),
    ...(entry["openSidebar"] === true ? { openSidebar: true } : {}),
    ...(entry["showStickyLauncher"] === true ? { showStickyLauncher: true } : {}),
  };
  return ruleHasEffect(rule) ? rule : null;
}

/** Coerce a stored list, dropping anything unusable and de-duplicating by host (first rule for a host wins). */
export function normalizeSiteAutoActions(raw: unknown): SiteAutoAction[] {
  if (!Array.isArray(raw)) return [...DEFAULT_SITE_AUTO_ACTIONS];
  const seen = new Set<string>();
  const out: SiteAutoAction[] = [];
  for (const item of raw) {
    const rule = coerceSiteAutoAction(item);
    if (rule === null) continue;
    const host = hostOf(rule.domain);
    if (seen.has(host)) continue;
    seen.add(host);
    out.push(rule);
  }
  return out;
}

/** Whether a rule's domain covers a host. */
function ruleCoversHost(rule: SiteAutoAction, host: string): boolean {
  const domain = hostOf(rule.domain);
  if (domain === "" || host === "") return false;
  return host === domain || host.endsWith(`.${domain}`);
}

/**
 * The auto-action rule that applies to a URL, or null.
 *
 * Most-specific-wins, exactly like capture rules: the longest matching domain takes it, so a rule for
 * `docs.example.com` beats a rule for `example.com` without either needing to know the other exists. Order
 * in the list is not significant.
 */
export function matchAutoAction(rules: readonly SiteAutoAction[], url: string): SiteAutoAction | null {
  const host = hostOf(url);
  if (host === "") return null;
  let best: SiteAutoAction | null = null;
  let bestLength = -1;
  for (const rule of rules) {
    if (!ruleCoversHost(rule, host)) continue;
    const length = hostOf(rule.domain).length;
    if (length > bestLength) {
      bestLength = length;
      best = rule;
    }
  }
  return best;
}

/** A short human summary of what a rule does, for the settings list. */
export function summarizeAutoAction(rule: SiteAutoAction): string {
  const parts: string[] = [];
  switch (rule.onSelect) {
    case "highlight":
      parts.push(`highlight in ${rule.color ?? "yellow"}${rule.style === "underline" ? " (underline)" : ""}`);
      break;
    case "copy":
      parts.push(`copy as ${(rule.copyFormat ?? "quote").replace("-", " ")}`);
      break;
    case "sticky":
      parts.push(`sticky note (${rule.color ?? "yellow"})`);
      break;
    case "note":
      parts.push("open the note editor");
      break;
    case "none":
      break;
  }
  if (rule.onSelect !== "none" && rule.alsoShowToolbar === true) parts.push("then show the toolbar");
  if (rule.openSidebar === true) parts.push("open the sidebar");
  if (rule.showStickyLauncher === true) parts.push("show the sticky launcher");
  return parts.length === 0 ? "no effect" : parts.join(" · ");
}
