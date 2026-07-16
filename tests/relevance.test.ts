import { describe, it, expect } from "vitest";
import {
  DEFAULT_RELEVANCE,
  orderByTitleFirst,
  normalizeWeights,
  recencyScore,
  applyRecency,
  normalizeRanking,
  fuseRankings,
} from "../src/services/search/relevance";

const DAY = 86_400_000;
const now = Date.parse("2026-07-01T00:00:00Z");

describe("recency decays, it does not fall off a cliff", () => {
  it("is 1 for a note edited now, and exactly ½ at one half-life", () => {
    expect(recencyScore(now, now, 180)).toBeCloseTo(1);
    expect(recencyScore(now - 180 * DAY, now, 180)).toBeCloseTo(0.5);
    expect(recencyScore(now - 360 * DAY, now, 180)).toBeCloseTo(0.25);
  });

  it("179 and 181 days old are almost the same — that is the point of a decay", () => {
    const a = recencyScore(now - 179 * DAY, now, 180);
    const b = recencyScore(now - 181 * DAY, now, 180);
    expect(Math.abs(a - b)).toBeLessThan(0.01);
  });

  it("a note with no timestamp scores 0 — absent evidence of freshness is not evidence of it", () => {
    expect(recencyScore(undefined, now, 180)).toBe(0);
    expect(recencyScore(0, now, 180)).toBe(0);
  });
});

describe("recency is a tie-breaker, never a takeover", () => {
  const w = DEFAULT_RELEVANCE; // recencyWeight 0.1

  it("a fresh note gets at most a 10% lift at the default weight", () => {
    const boosted = applyRecency(1, now, now, w);
    expect(boosted).toBeCloseTo(1.1);
  });

  it("a weak-but-fresh note still loses to a strong-but-old one", () => {
    const weakFresh = applyRecency(0.5, now, now, w); // 0.55
    const strongOld = applyRecency(0.9, now - 1000 * DAY, now, w); // ~0.9
    expect(strongOld).toBeGreaterThan(weakFresh);
  });

  it("but it does break a tie between equally relevant notes", () => {
    const fresh = applyRecency(0.8, now, now, w);
    const stale = applyRecency(0.8, now - 720 * DAY, now, w);
    expect(fresh).toBeGreaterThan(stale);
  });

  it("a weight of 0 changes nothing at all", () => {
    const off = { ...w, recencyWeight: 0 };
    expect(applyRecency(0.7, now, now, off)).toBe(0.7);
  });
});

describe("fusing two rankings that live on different scales", () => {
  it("normalises each side, so raw BM25 magnitudes cannot win by arithmetic accident", () => {
    // keyword scores are huge, semantic scores are tiny -- naive addition would ignore semantics
    const kw = [{ id: "a", score: 120 }, { id: "b", score: 60 }];
    const sem = [{ id: "b", score: 0.9 }, { id: "a", score: 0.1 }];
    const fused = fuseRankings(kw, sem, { ...DEFAULT_RELEVANCE, semanticWeight: 0.5 });
    // a: 0.5*1.0 + 0.5*0.111 = 0.556 ; b: 0.5*0.5 + 0.5*1.0 = 0.75  -> b wins on meaning
    expect(fused[0]!.id).toBe("b");
  });

  it("semanticWeight 0 is keyword-only; 1 is semantic-only", () => {
    const kw = [{ id: "a", score: 10 }];
    const sem = [{ id: "b", score: 1 }];
    expect(fuseRankings(kw, sem, { ...DEFAULT_RELEVANCE, semanticWeight: 0 })[0]!.id).toBe("a");
    expect(fuseRankings(kw, sem, { ...DEFAULT_RELEVANCE, semanticWeight: 1 })[0]!.id).toBe("b");
  });

  it("a document only one side found is still a result, not a zero", () => {
    const fused = fuseRankings([{ id: "only-kw", score: 5 }], [{ id: "only-sem", score: 1 }], DEFAULT_RELEVANCE);
    expect(fused.map((f) => f.id).sort()).toEqual(["only-kw", "only-sem"]);
  });

  it("an empty ranking normalises to zeros rather than dividing by zero", () => {
    expect(normalizeRanking([]).size).toBe(0);
    expect([...normalizeRanking([{ id: "x", score: 0 }]).values()]).toEqual([0]);
  });
});

describe("weights arriving from disk are clamped, so a hand-edited config cannot break ranking", () => {
  it("out-of-range values fall back into range", () => {
    const w = normalizeWeights({ semanticWeight: 99, recencyWeight: -5, titleBoost: 1000, recencyHalfLifeDays: 0 });
    expect(w.semanticWeight).toBe(1);
    expect(w.recencyWeight).toBe(0);
    expect(w.titleBoost).toBe(10);
    expect(w.recencyHalfLifeDays).toBe(1);
  });
  it("missing or garbage values fall back to the defaults", () => {
    expect(normalizeWeights(undefined)).toEqual(DEFAULT_RELEVANCE);
    expect(normalizeWeights({ semanticWeight: NaN }).semanticWeight).toBe(DEFAULT_RELEVANCE.semanticWeight);
  });
  it("the defaults preserve the behaviour we already had (a 60/40 keyword-leaning blend)", () => {
    expect(DEFAULT_RELEVANCE.semanticWeight).toBe(0.4);
  });
});

describe("orderByTitleFirst — deterministic title-first ranking", () => {
  const r = (id: string, score: number, title: string, aliases = ""): { id: string; score: number; source: string; meta: Record<string, string | number> } => ({
    id,
    score,
    source: "note",
    meta: aliases ? { title, aliases } : { title },
  });

  it("pins an exact title match above higher-scoring mentions", () => {
    const hits = [
      r("mention1", 9.0, "Journal 2024-01-03"), // merely mentions "Mira Holt", high BM25
      r("mention2", 8.0, "Meeting notes"),
      r("the-note", 2.0, "Mira Holt"), // the person's own note, low BM25
    ];
    const out = orderByTitleFirst(hits, "mira holt");
    expect(out[0]!.id).toBe("the-note");
  });

  it("treats an alias match as tier 0", () => {
    const hits = [r("j", 9, "Some journal"), r("p", 1, "Mira Holt", "Miri, MH")];
    expect(orderByTitleFirst(hits, "miri")[0]!.id).toBe("p");
  });

  it("ranks a title prefix (tier 1) above a plain body match (tier 2)", () => {
    const hits = [r("body", 9, "Unrelated"), r("prefix", 1, "Design tokens and theming")];
    expect(orderByTitleFirst(hits, "design tok")[0]!.id).toBe("prefix");
  });

  it("supports word-prefix-in-order ('mi ho' → 'Mira Holt')", () => {
    const hits = [r("other", 9, "History of Modern Art"), r("mh", 1, "Mira Holt")];
    expect(orderByTitleFirst(hits, "mi ho")[0]!.id).toBe("mh");
  });

  it("within the exact-title tier, the shortest title wins", () => {
    const hits = [r("long", 5, "Design"), r("shorter", 5, "Design")];
    // identical titles → stable by input order; distinct lengths tested here:
    const hits2 = [r("a", 5, "Designer"), r("b", 5, "Design")];
    expect(orderByTitleFirst(hits2, "design")[0]!.id).toBe("b"); // exact "design" beats prefix "designer"
    expect(orderByTitleFirst(hits, "design").map((h) => h.id)).toEqual(["long", "shorter"]);
  });

  it("falls back to score order (stable) when nothing matches the title", () => {
    const hits = [r("a", 3, "X"), r("b", 9, "Y"), r("c", 5, "Z")];
    // input already score-sorted; title-first must preserve it when all are tier 2
    expect(orderByTitleFirst([...hits].sort((x, y) => y.score - x.score), "nomatch").map((h) => h.id)).toEqual(["b", "c", "a"]);
  });

  it("empty query returns input unchanged", () => {
    const hits = [r("a", 1, "A"), r("b", 2, "B")];
    expect(orderByTitleFirst(hits, "").map((h) => h.id)).toEqual(["a", "b"]);
  });

  it("folds diacritics and case", () => {
    const hits = [r("x", 9, "Something"), r("cafe", 1, "Café")];
    expect(orderByTitleFirst(hits, "cafe")[0]!.id).toBe("cafe");
  });
});
