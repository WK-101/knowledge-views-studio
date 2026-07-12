import { describe, it, expect } from "vitest";
import { splitPassages, questionTerms, scorePassageKeyword } from "../src/services/search/qa";
import { tokenize } from "../src/services/search/tokenize";

describe("questionTerms", () => {
  it("strips stopwords and question words", () => {
    expect(questionTerms("What is the training objective of BERT?")).toEqual(["training", "objective", "bert"]);
  });
});

describe("splitPassages", () => {
  it("splits into sentence-ish passages with offsets, merging short fragments", () => {
    const text = "Short. Also short. This is a longer sentence that carries some real content about the topic at hand.";
    const ps = splitPassages(text, 40);
    expect(ps.length).toBeGreaterThanOrEqual(1);
    // offsets map back to the source
    for (const p of ps) expect(text.slice(p.start, p.end).trim()).toBe(p.text);
    // the long sentence is present
    expect(ps.some((p) => p.text.includes("longer sentence"))).toBe(true);
  });
  it("handles empty text", () => {
    expect(splitPassages("")).toEqual([]);
  });
});

describe("scorePassageKeyword", () => {
  const idf = (t: string) => ({ transformer: 4, the: 0.1, model: 2 })[t] ?? 1;
  it("passages covering more (rarer) question terms score higher", () => {
    const q = questionTerms("what is the transformer model");
    const strong = scorePassageKeyword(q, tokenize("the transformer model uses attention"), idf);
    const weak = scorePassageKeyword(q, tokenize("the cat sat on the mat"), idf);
    const partial = scorePassageKeyword(q, tokenize("this model is simple"), idf);
    expect(strong).toBeGreaterThan(partial);
    expect(partial).toBeGreaterThan(weak);
    expect(weak).toBe(0);
  });
  it("no question terms → zero", () => {
    expect(scorePassageKeyword([], tokenize("anything"), idf)).toBe(0);
  });
});
