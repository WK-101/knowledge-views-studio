import type { TextAnchor } from "./protocol";

/**
 * Finding an anchor in the page's raw text, for painting.
 *
 * `findAnchor` (shared/anchor.ts) answers *whether* a passage still exists, comparing squashed text —
 * right for a yes/no, useless for painting, because squashing moves every offset and a highlight painted at
 * a squashed position lands mid-word in the real DOM. Painting needs offsets into the text exactly as the
 * DOM concatenates it, whitespace and all.
 *
 * So the quote is matched with a whitespace-tolerant pattern: any run of whitespace in the quote matches
 * any run in the page. That's precisely the difference a re-render introduces — a `<br>` becomes a space, a
 * reflow adds a newline — and precisely what must not cause a miss. Everything else must match exactly;
 * fuzzy text matching would paint the wrong words, which nobody would notice until they trusted it.
 */

export interface LocatedAnchor {
  /** Offsets into the raw text, [start, end). */
  readonly start: number;
  readonly end: number;
}

const escapeRegex = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** A pattern matching the text with any whitespace run standing for any other. */
function tolerantPattern(text: string): RegExp | null {
  const parts = text.trim().split(/\s+/).map(escapeRegex);
  if (parts.length === 0 || parts[0] === "") return null;
  // Bounded gap: whitespace runs in real DOM text can be long (indentation), but an unbounded \s+ across
  // a pathological page invites catastrophic scanning. 64 covers any honest re-render.
  return new RegExp(parts.join("[\\s\\u00a0]{1,64}"), "g");
}

function allMatches(pattern: RegExp, haystack: string): LocatedAnchor[] {
  const out: LocatedAnchor[] = [];
  for (const match of haystack.matchAll(pattern)) {
    if (match.index !== undefined) out.push({ start: match.index, end: match.index + match[0].length });
    if (out.length > 200) break; // A page where a quote appears 200 times is not one we can disambiguate.
  }
  return out;
}

/** How well the text around an occurrence agrees with the anchor's remembered context. */
function contextScore(haystack: string, at: LocatedAnchor, anchor: TextAnchor): number {
  const squash = (s: string): string => s.replace(/\s+/g, " ").trim().toLowerCase();
  const prefix = squash(anchor.prefix ?? "");
  const suffix = squash(anchor.suffix ?? "");
  let score = 0;
  if (prefix !== "") {
    const before = squash(haystack.slice(Math.max(0, at.start - prefix.length * 2), at.start));
    if (before.endsWith(prefix)) score += 2;
    else if (prefix.length > 8 && before.includes(prefix.slice(-8))) score += 1;
  }
  if (suffix !== "") {
    const after = squash(haystack.slice(at.end, at.end + suffix.length * 2));
    if (after.startsWith(suffix)) score += 2;
    else if (suffix.length > 8 && after.includes(suffix.slice(0, 8))) score += 1;
  }
  return score;
}

/**
 * Locate an anchor in raw text, or null.
 *
 * One occurrence: take it — that tolerance is what lets a highlight survive the text around it being
 * rewritten. Several: the remembered context decides; with no context and no clear winner, null, because a
 * highlight painted on the wrong occurrence is worse than one reported missing.
 */
export function locateAnchor(rawText: string, anchor: TextAnchor): LocatedAnchor | null {
  const pattern = tolerantPattern(anchor.exact);
  if (pattern === null) return null;
  const found = allMatches(pattern, rawText);
  if (found.length === 0) return null;
  const only = found[0];
  if (found.length === 1 && only !== undefined) return only;

  let best: LocatedAnchor | null = null;
  let bestScore = 0;
  let contested = false;
  for (const at of found) {
    const score = contextScore(rawText, at, anchor);
    if (score > bestScore) {
      best = at;
      bestScore = score;
      contested = false;
    } else if (score === bestScore && score > 0) {
      contested = true;
    }
  }
  // Two occurrences scoring equally well is a tie the context can't break; refuse rather than guess.
  return contested || bestScore === 0 ? null : best;
}
