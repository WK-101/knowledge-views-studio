import type { TextAnchor } from "./protocol";

/**
 * Finding a highlight again.
 *
 * A page is not a document that holds still. Adverts load, comments render, a paragraph gets edited, the
 * layout changes on a narrower window — so any anchor built from positions points somewhere else by the time
 * anyone comes back. What survives is the text itself, quoted, with a little of what surrounds it to tell
 * two identical sentences apart.
 *
 * When the passage genuinely has gone, finding nothing is the right answer. A highlight silently reattached
 * to the wrong sentence is worse than one reported missing, because nobody would ever notice.
 */

/** How much surrounding text to keep. Enough to disambiguate; not so much that an edit nearby breaks it. */
const CONTEXT = 32;

function squash(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

/** Build an anchor from a selection and the text it sits in. */
export function buildAnchor(fullText: string, exact: string, offset?: number): TextAnchor {
  const cleanExact = squash(exact);
  if (cleanExact === "") return { exact: "" };

  const haystack = fullText;
  const at = offset !== undefined && offset >= 0 ? offset : haystack.indexOf(exact);
  if (at < 0) return { exact: cleanExact };

  const prefix = squash(haystack.slice(Math.max(0, at - CONTEXT), at));
  const suffix = squash(haystack.slice(at + exact.length, at + exact.length + CONTEXT));
  return {
    exact: cleanExact,
    ...(prefix !== "" ? { prefix } : {}),
    ...(suffix !== "" ? { suffix } : {}),
  };
}

/**
 * Locate an anchor in text, returning where it starts or -1.
 *
 * Tries the surrounding context first, since that's what distinguishes one occurrence from another. Falling
 * back to the quote alone is deliberate — a page whose wording around the highlight changed shouldn't lose
 * the highlight — but only when the quote appears exactly once, because choosing between several would be
 * guessing.
 */
export function findAnchor(fullText: string, anchor: TextAnchor): number {
  const exact = squash(anchor.exact);
  if (exact === "") return -1;
  const haystack = squash(fullText);
  const prefix = squash(anchor.prefix ?? "");
  const suffix = squash(anchor.suffix ?? "");

  // Every place the quote appears. Usually one; the interesting cases are when it isn't.
  const positions: number[] = [];
  for (let at = haystack.indexOf(exact); at >= 0; at = haystack.indexOf(exact, at + 1)) {
    positions.push(at);
  }
  if (positions.length === 0) return -1;

  // A single occurrence needs no disambiguating, and accepting it is what lets a highlight survive the
  // text around it being rewritten.
  const only = positions[0];
  if (positions.length === 1 && only !== undefined) return only;

  // Several, and nothing to tell them apart: refuse rather than attach the note to an arbitrary one.
  if (prefix === "" && suffix === "") return -1;

  // Compare what actually surrounds each occurrence against what surrounded the original. Deliberately
  // scored rather than matched exactly, so one side changing doesn't discard a perfectly good anchor.
  const SLACK = 4;
  let best = -1;
  let bestScore = 0;
  for (const at of positions) {
    const before = haystack.slice(Math.max(0, at - prefix.length - SLACK), at).trimEnd();
    const after = haystack.slice(at + exact.length, at + exact.length + suffix.length + SLACK).trimStart();
    let score = 0;
    if (prefix !== "") {
      if (before.endsWith(prefix)) score += 2;
      else if (prefix.length > 8 && before.includes(prefix.slice(-8))) score += 1;
    }
    if (suffix !== "") {
      if (after.startsWith(suffix)) score += 2;
      else if (suffix.length > 8 && after.includes(suffix.slice(0, 8))) score += 1;
    }
    if (score > bestScore) {
      bestScore = score;
      best = at;
    }
  }
  return bestScore > 0 ? best : -1;
}

/** Whether an anchor still resolves in this text. */
export function anchorResolves(fullText: string, anchor: TextAnchor): boolean {
  return findAnchor(fullText, anchor) >= 0;
}

/** A short, readable form of a highlight, for listing annotations without showing walls of text. */
export function anchorSummary(anchor: TextAnchor, limit = 90): string {
  const exact = squash(anchor.exact);
  return exact.length <= limit ? exact : `${exact.slice(0, limit).trimEnd()}…`;
}
