/**
 * The relevance model, made explicit.
 *
 * Until now the numbers that decide which result you see first were constants buried in the code: a
 * hybrid blend of 60/40 that I chose because it sounded reasonable, and field boosts of 3× / 2× / 1.6×
 * that I also chose because they sounded reasonable. Neither was measured. Hiding an unmeasured guess
 * inside a black box is worse than exposing it — at least exposed, someone can disagree with it.
 *
 * So the weights live here, they have names, they have defaults, and the user can change them.
 */

export interface RelevanceWeights {
  /** In Hybrid mode: how much the semantic score counts against the keyword score. 0 = keyword only. */
  readonly semanticWeight: number;
  /** How strongly a recently-edited note is favoured. 0 = not at all. */
  readonly recencyWeight: number;
  /** Days after which a note's recency bonus has halved. */
  readonly recencyHalfLifeDays: number;
  /** How much a match in the title outranks the same match in the body. */
  readonly titleBoost: number;
  /** Likewise for a heading. */
  readonly headingBoost: number;
  /** Likewise for a tag. */
  readonly tagBoost: number;
}

/**
 * Defaults.
 *
 * `semanticWeight` keeps the behaviour we already had (a 60/40 keyword-leaning blend) — changing it
 * silently would be a worse sin than having picked it badly in the first place.
 *
 * `recencyHalfLifeDays: 180` is not my number: it is the value Obsidian Seek arrived at after evaluating
 * relevance across a large query set. Borrowing a figure someone actually measured beats inventing one
 * I did not. The *weight* is deliberately small — recency should break ties between comparably relevant
 * notes, never drag a weak-but-fresh note above a strong-but-old one.
 */
export const DEFAULT_RELEVANCE: RelevanceWeights = {
  semanticWeight: 0.4,
  recencyWeight: 0.1,
  recencyHalfLifeDays: 180,
  titleBoost: 3,
  headingBoost: 2,
  tagBoost: 1.6,
};

/** Clamp settings that arrive from disk, so a hand-edited config cannot produce nonsense rankings. */
export function normalizeWeights(raw: Partial<RelevanceWeights> | undefined): RelevanceWeights {
  const clamp = (v: unknown, lo: number, hi: number, fallback: number): number => {
    const n = typeof v === "number" && Number.isFinite(v) ? v : fallback;
    return Math.min(hi, Math.max(lo, n));
  };
  return {
    semanticWeight: clamp(raw?.semanticWeight, 0, 1, DEFAULT_RELEVANCE.semanticWeight),
    recencyWeight: clamp(raw?.recencyWeight, 0, 1, DEFAULT_RELEVANCE.recencyWeight),
    recencyHalfLifeDays: clamp(raw?.recencyHalfLifeDays, 1, 3650, DEFAULT_RELEVANCE.recencyHalfLifeDays),
    titleBoost: clamp(raw?.titleBoost, 1, 10, DEFAULT_RELEVANCE.titleBoost),
    headingBoost: clamp(raw?.headingBoost, 1, 10, DEFAULT_RELEVANCE.headingBoost),
    tagBoost: clamp(raw?.tagBoost, 1, 10, DEFAULT_RELEVANCE.tagBoost),
  };
}

const DAY_MS = 86_400_000;

/**
 * How "fresh" a note is, from 1 (edited just now) decaying by half every half-life, approaching 0.
 *
 * Exponential decay rather than a cliff: a note edited 179 days ago and one edited 181 days ago should
 * not be treated as belonging to different worlds. A note with no timestamp scores 0 — absent evidence
 * of freshness is not evidence of freshness.
 */
export function recencyScore(mtime: number | undefined, now: number, halfLifeDays: number): number {
  if (typeof mtime !== "number" || !Number.isFinite(mtime) || mtime <= 0) return 0;
  const ageDays = Math.max(0, (now - mtime) / DAY_MS);
  if (halfLifeDays <= 0) return 0;
  return Math.pow(0.5, ageDays / halfLifeDays);
}

/**
 * Apply the recency bonus to a relevance score.
 *
 * Multiplicative, and bounded by `1 + recencyWeight`: at the default weight a brand-new note gets at
 * most a 10% lift. That makes recency a tie-breaker, which is what it should be — a note that barely
 * matches your query does not become the right answer merely by being recent.
 */
export function applyRecency(score: number, mtime: number | undefined, now: number, w: RelevanceWeights): number {
  if (w.recencyWeight <= 0) return score;
  return score * (1 + w.recencyWeight * recencyScore(mtime, now, w.recencyHalfLifeDays));
}

export interface Scored {
  readonly id: string;
  readonly score: number;
}

/** Scale a ranking to 0..1 by its own maximum, so two rankings on different scales can be compared. */
export function normalizeRanking(items: readonly Scored[]): Map<string, number> {
  const max = items.reduce((m, r) => Math.max(m, r.score), 0);
  if (max <= 0) return new Map(items.map((r) => [r.id, 0]));
  return new Map(items.map((r) => [r.id, r.score / max]));
}

/**
 * Blend a keyword ranking with a semantic one.
 *
 * Each is normalised against its own maximum first, because BM25 scores and cosine similarities live on
 * incomparable scales — adding them raw would let whichever happens to produce bigger numbers win by
 * arithmetic accident rather than by relevance. A document that only one side found keeps its score,
 * scaled by that side's weight: it is a real result, not a zero.
 */
export function fuseRankings(keyword: readonly Scored[], semantic: readonly Scored[], w: RelevanceWeights): Scored[] {
  const kw = normalizeRanking(keyword);
  const sem = normalizeRanking(semantic);
  const alpha = w.semanticWeight;

  const ids = new Set<string>([...kw.keys(), ...sem.keys()]);
  const out: Scored[] = [];
  for (const id of ids) {
    const score = (1 - alpha) * (kw.get(id) ?? 0) + alpha * (sem.get(id) ?? 0);
    if (score > 0) out.push({ id, score });
  }
  out.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
  return out;
}
