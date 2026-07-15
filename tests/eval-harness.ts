import { SearchIndex, type SearchOptions } from "../src/services/search/search-index";
import type { RelevanceWeights } from "../src/services/search/relevance";
import {
  mean,
  ndcgAtK,
  precisionAtK,
  recallAtK,
  reciprocalRank,
} from "../src/services/search/eval-metrics";
import { EVAL_CORPUS, EVAL_QUERIES, type EvalQuery } from "./fixtures/eval-corpus";

/**
 * Runs the evaluation query set against a real {@link SearchIndex} built from the corpus, and reduces the
 * results to the standard metrics. This is the bridge between "the engine returns some ranking" and "here
 * is a number for how good that ranking is". Kept out of any single test so both the reporting test and
 * the weight-tuning test can drive it.
 */

export interface QueryResult {
  readonly query: string;
  readonly ranking: readonly string[];
  readonly relevant: ReadonlySet<string>;
  readonly precisionAt5: number;
  readonly recallAt5: number;
  readonly reciprocalRank: number;
  readonly ndcgAt10: number;
}

export interface EvalReport {
  readonly perQuery: readonly QueryResult[];
  readonly meanPrecisionAt5: number;
  readonly meanRecallAt5: number;
  readonly mrr: number;
  readonly meanNdcgAt10: number;
}

/** Build a fresh index over the eval corpus. Field boosts come from the weights so tuning can vary them. */
export function buildEvalIndex(): SearchIndex {
  const index = new SearchIndex();
  for (const doc of EVAL_CORPUS) {
    // Model the same field structure the real indexer uses: title/heading/tag are the boosted facets,
    // body is the plain text. Headings and tags are joined into one field value each.
    const fields: Record<string, string> = { title: doc.title };
    if (doc.headings?.length) fields["heading"] = doc.headings.join(" ");
    if (doc.tags?.length) fields["tag"] = doc.tags.join(" ");
    index.add({ id: doc.id, text: doc.body, fields, source: "note" });
  }
  return index;
}

/** Field boosts derived from the tunable weights, in the shape SearchOptions expects. */
export function fieldBoostsFrom(w: RelevanceWeights): Record<string, number> {
  return { title: w.titleBoost, heading: w.headingBoost, tag: w.tagBoost };
}

function evaluateQuery(index: SearchIndex, q: EvalQuery, options: SearchOptions): QueryResult {
  const results = index.search(q.query, { limit: 10, matchMode: "any", ...options });
  const ranking = results.map((r) => r.id);
  const relevant = new Set(q.relevant);
  return {
    query: q.query,
    ranking,
    relevant,
    precisionAt5: precisionAtK(ranking, relevant, 5),
    recallAt5: recallAtK(ranking, relevant, 5),
    reciprocalRank: reciprocalRank(ranking, relevant),
    ndcgAt10: ndcgAtK(ranking, relevant, 10),
  };
}

/** Run the whole query set and aggregate. `weights` supplies the field boosts under test. */
export function runEval(weights: RelevanceWeights): EvalReport {
  const index = buildEvalIndex();
  const options: SearchOptions = { fieldBoosts: fieldBoostsFrom(weights) };
  const perQuery = EVAL_QUERIES.map((q) => evaluateQuery(index, q, options));
  return {
    perQuery,
    meanPrecisionAt5: mean(perQuery.map((r) => r.precisionAt5)),
    meanRecallAt5: mean(perQuery.map((r) => r.recallAt5)),
    mrr: mean(perQuery.map((r) => r.reciprocalRank)),
    meanNdcgAt10: mean(perQuery.map((r) => r.ndcgAt10)),
  };
}

/** A human-readable one-line summary per query, for eyeballing where the ranker wins and loses. */
export function formatReport(report: EvalReport): string {
  const lines: string[] = [];
  lines.push("query                                          P@5   R@5   RR    nDCG@10  ranking");
  for (const r of report.perQuery) {
    const top = r.ranking.slice(0, 5).map((id) => (r.relevant.has(id) ? `[${id}]` : id)).join(" ");
    lines.push(
      `${r.query.slice(0, 44).padEnd(44)}  ${r.precisionAt5.toFixed(2)}  ${r.recallAt5.toFixed(2)}  ` +
        `${r.reciprocalRank.toFixed(2)}  ${r.ndcgAt10.toFixed(2)}     ${top}`,
    );
  }
  lines.push("");
  lines.push(
    `MEAN  P@5=${report.meanPrecisionAt5.toFixed(3)}  R@5=${report.meanRecallAt5.toFixed(3)}  ` +
      `MRR=${report.mrr.toFixed(3)}  nDCG@10=${report.meanNdcgAt10.toFixed(3)}`,
  );
  return lines.join("\n");
}
