import { hostOf } from "./rules";

/**
 * Per-site note-template selection.
 *
 * The library (shared/note-templates.ts) makes templates reusable; this makes choosing one automatic. A
 * capture from arXiv should become an "Academic paper" note without anyone picking it each time, exactly the
 * friction the per-site capture rules already remove for *which view*. So this mirrors that machinery —
 * matching by host, most-specific rule wins (`shared/rules.ts`) — but selects a *template* rather than a
 * view. It's a separate, independent layer: the site rule can decide the view, and this can decide the
 * note's shape, without either knowing about the other.
 *
 * Pure and transport-agnostic, like the rest of the capture core: the plugin consults it when authoring a
 * captured note, and a test can drive it with a plain list and a URL.
 */

export interface TemplateRule {
  /** A host, without scheme or path. Subdomains match unless a more specific rule claims them. */
  readonly host: string;
  /** The note template (by id, from the library) captures from this host should use. */
  readonly templateId: string;
}

/** Coerce one stored (untrusted) value into a TemplateRule, or null. Both fields must be non-empty. */
export function coerceTemplateRule(raw: unknown): TemplateRule | null {
  if (raw === null || typeof raw !== "object") return null;
  const obj = raw as Record<string, unknown>;
  const host = typeof obj["host"] === "string" ? obj["host"].trim() : "";
  const templateId = typeof obj["templateId"] === "string" ? obj["templateId"].trim() : "";
  if (hostOf(host) === "" || templateId === "") return null;
  return { host, templateId };
}

/** Coerce a stored list, dropping unusable entries and de-duplicating by host (first writer wins). */
export function normalizeTemplateRules(raw: unknown): TemplateRule[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: TemplateRule[] = [];
  for (const item of raw) {
    const rule = coerceTemplateRule(item);
    if (rule === null) continue;
    const key = hostOf(rule.host);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(rule);
  }
  return out;
}

/**
 * The template rule that applies to a URL, or null.
 *
 * Most-specific host wins (ties to the longer host), so `scholar.google.com` can select a different template
 * from `google.com`. Order in the list is not significant — the same guarantee `matchRule` gives for views.
 */
export function matchTemplateRule(rules: readonly TemplateRule[], url: string): TemplateRule | null {
  const host = hostOf(url);
  if (host === "") return null;

  let best: TemplateRule | null = null;
  let bestLength = -1;
  for (const rule of rules) {
    const domain = hostOf(rule.host);
    if (domain === "") continue;
    if (host === domain || host.endsWith(`.${domain}`)) {
      if (domain.length > bestLength) {
        bestLength = domain.length;
        best = rule;
      }
    }
  }
  return best;
}
