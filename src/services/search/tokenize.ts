/**
 * Text normalisation + tokenisation for the search engine. Kept pure and dependency-free so it can be
 * shared by the indexer and the query parser (they MUST agree, or queries won't match the index) and
 * unit-tested. Designed for scale: a single linear pass, no per-token allocation beyond the result.
 */

/** Fold text to a canonical form: decompose accents and drop them, then lowercase. So "Résumé" and
 *  "resume" tokenise identically and search is accent-insensitive. */
export function foldText(input: string): string {
  return input.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
}

// Unicode letters + numbers form terms; everything else is a boundary. Marks (\p{M}) are kept so
// scripts that compose (already NFKD-folded above for Latin) don't shatter.
const TOKEN_RE = /[\p{L}\p{N}][\p{L}\p{N}\p{M}]*/gu;

/** Longest term we keep — guards the index against base64 blobs / minified junk inflating the vocabulary. */
const MAX_TERM_LENGTH = 80;

/**
 * Tokenise into normalised terms in document order. The array index of a term IS its position, which is
 * what phrase/proximity matching relies on — no separate position bookkeeping needed.
 */
export function tokenize(input: string): string[] {
  const matches = foldText(input).match(TOKEN_RE);
  if (!matches) return [];
  const out: string[] = [];
  for (const m of matches) {
    if (m.length <= MAX_TERM_LENGTH) out.push(m);
  }
  return out;
}
