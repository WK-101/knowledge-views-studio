import { describe, expect, it } from "vitest";
import {
  meanReciprocalRank,
  ndcgAtK,
  precisionAtK,
  recallAtK,
  reciprocalRank,
} from "../src/services/search/eval-metrics";

// A ranking metric that is subtly wrong would silently flatter or damn the ranker, making the whole
// evaluation worthless. So every value below is computed by hand in the comment and asserted exactly.
const rel = (...ids: string[]): ReadonlySet<string> => new Set(ids);

describe("precision@k", () => {
  it("counts relevant hits in the top k over k", () => {
    // ranking a,b,c,d,e; relevant {a,c,e}; top 5 has 3 hits -> 3/5
    expect(precisionAtK(["a", "b", "c", "d", "e"], rel("a", "c", "e"), 5)).toBeCloseTo(0.6);
  });

  it("divides by k, not by results returned — a short result set does not fill the page", () => {
    // only two results, both relevant; at k=5 that is 2/5, not 2/2
    expect(precisionAtK(["a", "b"], rel("a", "b"), 5)).toBeCloseTo(0.4);
  });

  it("is 1 when every one of the top k is relevant", () => {
    expect(precisionAtK(["a", "b", "c"], rel("a", "b", "c", "d"), 3)).toBe(1);
  });

  it("is 0 when nothing in the top k is relevant", () => {
    expect(precisionAtK(["x", "y"], rel("a"), 2)).toBe(0);
  });
});

describe("recall@k", () => {
  it("counts relevant hits in the top k over the total relevant", () => {
    // relevant {a,c,e} (3 total); top 3 of a,b,c finds a,c -> 2/3
    expect(recallAtK(["a", "b", "c"], rel("a", "c", "e"), 3)).toBeCloseTo(2 / 3);
  });

  it("reaches 1 when the window is wide enough to include all relevant", () => {
    expect(recallAtK(["a", "b", "c", "e"], rel("a", "c", "e"), 4)).toBe(1);
  });

  it("is vacuously 1 when nothing is relevant", () => {
    expect(recallAtK(["a"], rel(), 5)).toBe(1);
  });
});

describe("reciprocal rank", () => {
  it("is 1 when the first result is relevant", () => {
    expect(reciprocalRank(["a", "b"], rel("a"))).toBe(1);
  });

  it("is 1/position of the first relevant result", () => {
    // first relevant at index 2 (rank 3) -> 1/3
    expect(reciprocalRank(["x", "y", "a", "b"], rel("a", "b"))).toBeCloseTo(1 / 3);
  });

  it("is 0 when no result is relevant", () => {
    expect(reciprocalRank(["x", "y"], rel("a"))).toBe(0);
  });
});

describe("nDCG@k", () => {
  it("is 1 for a perfect ranking (all relevant docs first)", () => {
    expect(ndcgAtK(["a", "b", "c"], rel("a", "b"), 3)).toBeCloseTo(1);
  });

  it("penalises a relevant doc pushed down the ranking", () => {
    // relevant {a}. Ranking x,x,a: DCG = 1/log2(4) = 0.5; IDCG = 1/log2(2) = 1; nDCG = 0.5
    expect(ndcgAtK(["x", "y", "a"], rel("a"), 3)).toBeCloseTo(0.5);
  });

  it("computes the known two-hit case exactly", () => {
    // ranking a,x,b; relevant {a,b}.
    // DCG  = 1/log2(2) + 0 + 1/log2(4)      = 1 + 0.5           = 1.5
    // IDCG = 1/log2(2) + 1/log2(3)          = 1 + 0.6309        = 1.6309
    // nDCG = 1.5 / 1.6309                    = 0.9197
    expect(ndcgAtK(["a", "x", "b"], rel("a", "b"), 3)).toBeCloseTo(0.9197, 3);
  });

  it("is 1 when nothing is relevant (vacuous)", () => {
    expect(ndcgAtK(["a"], rel(), 3)).toBe(1);
  });

  it("respects the cutoff k", () => {
    // relevant {a} at rank 4, but k=3 -> a is outside the window -> 0
    expect(ndcgAtK(["x", "y", "z", "a"], rel("a"), 3)).toBe(0);
  });
});

describe("mean reciprocal rank", () => {
  it("averages reciprocal ranks across queries", () => {
    // q1: first relevant at rank 1 -> 1; q2: first relevant at rank 2 -> 0.5; mean 0.75
    const mrr = meanReciprocalRank([
      { ranking: ["a", "b"], relevant: rel("a") },
      { ranking: ["x", "b"], relevant: rel("b") },
    ]);
    expect(mrr).toBeCloseTo(0.75);
  });
});
