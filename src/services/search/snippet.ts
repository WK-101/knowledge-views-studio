/**
 * Snippet generation: given a document's text and the query terms, pick the densest window of matches
 * and return it with character ranges for highlighting. Pure + testable. Works on the original text so
 * highlight offsets are correct (case-insensitive; accent variants may not highlight but still matched
 * in the index).
 */
export interface Snippet {
  readonly text: string;
  readonly ranges: readonly [number, number][];
  readonly prefix: boolean; // add a leading ellipsis
  readonly suffix: boolean; // add a trailing ellipsis
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Start position (a match's offset) whose following `maxLen` window covers the most matches. */
function bestWindow(hits: readonly [number, number][], maxLen: number): number {
  let bestStart = hits[0]![0];
  let bestCount = 0;
  let j = 0;
  for (let i = 0; i < hits.length; i++) {
    while (j < hits.length && hits[j]![0] - hits[i]![0] <= maxLen) j++;
    if (j - i > bestCount) {
      bestCount = j - i;
      bestStart = hits[i]![0];
    }
  }
  return bestStart;
}

export function makeSnippet(text: string, terms: readonly string[], maxLen = 240): Snippet {
  const clean = text.replace(/\s+/g, " ").trim();
  const uniq = [...new Set(terms.filter((t) => t !== ""))];
  if (uniq.length === 0 || clean === "") {
    return { text: clean.slice(0, maxLen), ranges: [], prefix: false, suffix: clean.length > maxLen };
  }
  const re = new RegExp(`(${uniq.map(escapeRegExp).join("|")})`, "giu");
  const hits: [number, number][] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(clean)) !== null) {
    hits.push([m.index, m.index + m[0].length]);
    if (m.index === re.lastIndex) re.lastIndex++;
    if (hits.length >= 500) break;
  }
  if (hits.length === 0) {
    return { text: clean.slice(0, maxLen), ranges: [], prefix: false, suffix: clean.length > maxLen };
  }
  const anchor = bestWindow(hits, maxLen);
  let start = Math.max(0, anchor - Math.floor(maxLen * 0.15));
  const space = clean.lastIndexOf(" ", start);
  if (space > 0 && start - space < 24) start = space + 1;
  const end = Math.min(clean.length, start + maxLen);
  const core = clean.slice(start, end);
  const ranges = hits
    .filter(([s, e]) => s >= start && e <= end)
    .map(([s, e]) => [s - start, e - start] as [number, number]);
  return { text: core, ranges, prefix: start > 0, suffix: end < clean.length };
}
