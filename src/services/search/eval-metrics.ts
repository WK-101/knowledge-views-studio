/**
 * Information-retrieval evaluation metrics.
 *
 * These are the instruments P6 uses to answer a question the 650 unit tests cannot: not "does search
 * return what the code says" but "does search return what a person actually wanted". Every function here
 * is pure and takes a *ranking* (the ordered list of doc ids the engine returned) plus the *relevant set*
 * (the ids a human judged relevant to that query), and reduces them to a number.
 *
 * The definitions are the standard ones, implemented plainly so they can be checked against hand-computed
 * values rather than trusted. A metric that is subtly wrong would flatter or damn the ranker for no
 * reason, which defeats the entire point of measuring.
 */

/**
 * Precision@k — of the top k results, what fraction were relevant.
 *
 * "Did the first page waste the user's time?" A precision@5 of 0.6 means three of the first five results
 * were worth showing. Divided by k (not by however many were returned), so a query that returns only two
 * results, both relevant, still scores 2/5 at k=5 — it did not fill the page.
 */
export function precisionAtK(ranking: readonly string[], relevant: ReadonlySet<string>, k: number): number {
  if (k <= 0) return 0;
  let hits = 0;
  for (let i = 0; i < Math.min(k, ranking.length); i++) {
    if (relevant.has(ranking[i]!)) hits++;
  }
  return hits / k;
}

/**
 * Recall@k — of all the relevant documents, what fraction appeared in the top k.
 *
 * "Did the user have to dig to find everything they needed?" The complement of precision: a search can be
 * precise (nothing junk up top) yet have poor recall (the other relevant docs are on page 3). Undefined
 * when nothing is relevant, reported as 1 — vacuously, everything relevant was found.
 */
export function recallAtK(ranking: readonly string[], relevant: ReadonlySet<string>, k: number): number {
  if (relevant.size === 0) return 1;
  let hits = 0;
  for (let i = 0; i < Math.min(k, ranking.length); i++) {
    if (relevant.has(ranking[i]!)) hits++;
  }
  return hits / relevant.size;
}

/**
 * Reciprocal rank — 1 / (position of the first relevant result), or 0 if none is found.
 *
 * "How far did the user scroll before the first useful hit?" First result relevant → 1. Third → 0.333.
 * Averaged across queries this is MRR (see {@link meanReciprocalRank}), the classic measure for "I want
 * one good answer, near the top".
 */
export function reciprocalRank(ranking: readonly string[], relevant: ReadonlySet<string>): number {
  for (let i = 0; i < ranking.length; i++) {
    if (relevant.has(ranking[i]!)) return 1 / (i + 1);
  }
  return 0;
}

/**
 * nDCG@k — normalised discounted cumulative gain.
 *
 * The most complete of the four: it rewards putting relevant results high, discounting each position by
 * log2(rank+1) so a relevant doc at rank 1 is worth more than one at rank 8, and normalises against the
 * *ideal* ordering (all relevant docs first) so the result is 0..1 and comparable across queries with
 * different numbers of relevant docs. Binary relevance here (a doc is relevant or not), which is what the
 * qrels encode.
 */
export function ndcgAtK(ranking: readonly string[], relevant: ReadonlySet<string>, k: number): number {
  if (relevant.size === 0) return 1;
  const dcg = (hits: readonly number[]): number =>
    hits.reduce((sum, rel, i) => sum + rel / Math.log2(i + 2), 0);

  const gains: number[] = [];
  for (let i = 0; i < Math.min(k, ranking.length); i++) {
    gains.push(relevant.has(ranking[i]!) ? 1 : 0);
  }
  // Ideal: as many 1s as there are relevant docs (capped at k), all up front.
  const idealCount = Math.min(k, relevant.size);
  const ideal = Array.from({ length: idealCount }, () => 1);

  const idcg = dcg(ideal);
  return idcg === 0 ? 0 : dcg(gains) / idcg;
}

/** Average of any per-query metric across the query set — the single number a run reports. */
export function mean(values: readonly number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

/** Mean reciprocal rank across queries — the headline "one good answer near the top" score. */
export function meanReciprocalRank(rankings: readonly { ranking: readonly string[]; relevant: ReadonlySet<string> }[]): number {
  return mean(rankings.map((r) => reciprocalRank(r.ranking, r.relevant)));
}
