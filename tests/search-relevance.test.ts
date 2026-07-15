import { describe, expect, it } from "vitest";
import { runEval } from "./eval-harness";
import { parseQuery } from "../src/services/search/query";
import { DEFAULT_RELEVANCE } from "../src/services/search/relevance";

/**
 * The relevance regression gate.
 *
 * The 650 other tests prove the search code does what it says. This one proves the search returns results
 * a person actually wanted, by running a human-judged query set (see fixtures/eval-corpus) through the
 * real engine and asserting the standard IR metrics clear thresholds. It is the answer to the standing
 * README caveat that "the search has never been evaluated for relevance" — it has now, and this keeps it
 * evaluated on every change.
 *
 * Thresholds are set just below the measured baseline, so this catches a regression (a code change that
 * makes ranking worse) without being so tight that noise fails the build. They are floors to defend and
 * ratchet upward, not targets that happen to pass today.
 *
 * Measured baseline at the time of writing (DEFAULT_RELEVANCE, after the lowercase-operator fix):
 *   P@5 = 0.280   R@5 = 0.967   MRR = 0.950   nDCG@10 = 0.910
 * P@5 is low by construction (most queries have 1–2 relevant docs, capping P@5 at 0.2–0.4), which is why
 * nDCG and MRR are the honest headline numbers here, not precision.
 */

describe("search relevance meets its measured baseline", () => {
  const report = runEval(DEFAULT_RELEVANCE);

  it("ranks a relevant result at or near the top (MRR)", () => {
    // MRR 0.95 means the first relevant hit is almost always in position 1. A drop here means the ranker
    // started burying good answers.
    expect(report.mrr).toBeGreaterThanOrEqual(0.9);
  });

  it("puts relevant results high, weighted by position (nDCG@10)", () => {
    expect(report.meanNdcgAt10).toBeGreaterThanOrEqual(0.88);
  });

  it("surfaces most of the relevant documents in the top 5 (recall)", () => {
    expect(report.meanRecallAt5).toBeGreaterThanOrEqual(0.93);
  });

  it("every query finds at least one relevant result (no total misses)", () => {
    // A reciprocal rank of 0 means the engine returned nothing relevant at all for that query — the worst
    // failure mode, and one no aggregate average should be allowed to hide.
    for (const q of report.perQuery) {
      expect(q.reciprocalRank, `"${q.query}" returned no relevant result: ${q.ranking.join(", ")}`).toBeGreaterThan(0);
    }
  });
});

describe("lowercase boolean words are search terms, not operators (bug found by the eval)", () => {
  // The evaluation surfaced this: "extraction and flavour" was being parsed as extraction AND flavour,
  // collapsing a broad query to the one document containing both words. Standard convention is that only
  // UPPERCASE AND/OR/NOT are operators.
  it("treats lowercase 'and' as an ordinary term", () => {
    const ast = parseQuery("extraction and flavour", { defaultOp: "or" });
    // With "and" as a term and OR as the default op, this is a 3-way OR, not a 2-way AND.
    expect(ast.type).toBe("or");
    if (ast.type === "or") {
      const terms = ast.children.flatMap((c) => (c.type === "term" ? [c.value] : []));
      expect(terms).toContain("and");
      expect(terms).toContain("extraction");
      expect(terms).toContain("flavour");
    }
  });

  it("still honours UPPERCASE AND as a boolean operator", () => {
    const ast = parseQuery("extraction AND flavour");
    expect(ast.type).toBe("and");
    if (ast.type === "and") {
      const terms = ast.children.flatMap((c) => (c.type === "term" ? [c.value] : []));
      expect(terms).toEqual(["extraction", "flavour"]); // "and" consumed as the operator, not a term
    }
  });

  it("treats lowercase 'or' and 'not' as terms too", () => {
    const ast = parseQuery("coffee or tea", { defaultOp: "and" });
    // "or" is a term, so this is a 3-way AND, and every word (including 'or') is a term.
    expect(ast.type).toBe("and");
    if (ast.type === "and") {
      const terms = ast.children.flatMap((c) => (c.type === "term" ? [c.value] : []));
      expect(terms).toContain("or");
    }
  });
});
