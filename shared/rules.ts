/**
 * Per-site rules.
 *
 * The single highest-value setting, because it removes a decision from every capture rather than adding
 * one. Papers from arXiv always belong in the same view, in the same shape; articles from a newspaper
 * almost always belong somewhere else entirely. Making that choice by hand each time is small enough to
 * tolerate and frequent enough to grate, which is exactly the kind of friction that quietly stops people
 * using a tool.
 *
 * Matching is by host, and the most specific rule wins — so `scholar.google.com` can behave differently from
 * `google.com` without either rule having to know the other exists.
 */

export interface DomainRule {
  /** A host, without scheme or path. Subdomains match unless a more specific rule claims them. */
  readonly domain: string;
  readonly viewId: string;
  readonly shape?: "row" | "note";
  /** Added to every capture from this site, as a comma-separated list. */
  readonly tags?: string;
}

/** Reduce a URL or host to a comparable hostname. */
export function hostOf(raw: string): string {
  const text = raw.trim().toLowerCase();
  if (text === "") return "";
  try {
    return new URL(text).hostname.replace(/^www\./, "");
  } catch {
    // Also accept a bare host, which is what someone types into a rule.
    return text
      .replace(/^[a-z]+:\/\//, "")
      .replace(/^www\./, "")
      .split("/")[0]
      ?.split(":")[0] ?? "";
  }
}

/** Whether a rule's domain covers a host. */
export function ruleMatches(rule: DomainRule, host: string): boolean {
  const domain = hostOf(rule.domain);
  if (domain === "" || host === "") return false;
  return host === domain || host.endsWith(`.${domain}`);
}

/**
 * The rule that applies to a URL, or null.
 *
 * Ties go to the longer domain, which is what makes a subdomain rule beat the parent it sits under. Order in
 * the list is not significant — a rule that only worked because of where it happened to sit would be a
 * miserable thing to debug.
 */
export function matchRule(rules: readonly DomainRule[], url: string): DomainRule | null {
  const host = hostOf(url);
  if (host === "") return null;

  let best: DomainRule | null = null;
  let bestLength = -1;
  for (const rule of rules) {
    if (!ruleMatches(rule, host)) continue;
    const length = hostOf(rule.domain).length;
    if (length > bestLength) {
      bestLength = length;
      best = rule;
    }
  }
  return best;
}

/** Reject a rule that can't do anything, so the list never fills with entries that quietly never fire. */
export function isUsableRule(rule: Partial<DomainRule>): rule is DomainRule {
  return hostOf(rule.domain ?? "") !== "" && (rule.viewId ?? "").trim() !== "";
}

/** Merge a rule's tags into a capture's, without repeating any. */
export function mergeTags(existing: string, ruleTags: string | undefined): string {
  const split = (value: string): string[] =>
    value
      .split(/[,;]/)
      .map((part) => part.trim())
      .filter((part) => part !== "");
  const seen = new Set<string>();
  const out: string[] = [];
  for (const tag of [...split(existing), ...split(ruleTags ?? "")]) {
    const key = tag.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(tag);
  }
  return out.join(", ");
}
