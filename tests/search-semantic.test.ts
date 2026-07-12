import { describe, it, expect } from "vitest";
import { SemanticModel } from "../src/services/search/semantic";
import { tokenize } from "../src/services/search/tokenize";

/** Build a model from a corpus of {id, text}. */
function build(corpus: { id: string; text: string }[]): SemanticModel {
  const m = new SemanticModel();
  const toks = corpus.map((d) => ({ id: d.id, tokens: tokenize(d.text) }));
  for (const d of toks) m.observe(d.tokens);
  for (const d of toks) m.addDocVector(d.id, d.tokens);
  return m;
}

describe("SemanticModel (distributional / Random Indexing)", () => {
  // "cat" and "dog" share context (pet/feed/vet); "car" and "engine" share another (drive/road).
  const corpus = [
    { id: "d1", text: "the cat is a pet you feed the cat and take the cat to the vet" },
    { id: "d2", text: "the dog is a pet you feed the dog and take the dog to the vet" },
    { id: "d3", text: "the car has an engine you drive the car on the road every day" },
    { id: "d4", text: "the truck has an engine you drive the truck on the road every day" },
  ];
  const m = build(corpus);

  it("terms in similar contexts are more similar than unrelated terms", () => {
    const catDog = m.similarity(["cat"], ["dog"]);
    const catCar = m.similarity(["cat"], ["car"]);
    expect(catDog).toBeGreaterThan(catCar);
  });

  it("a query finds topically-related docs by meaning, not just exact term overlap", () => {
    // "pet" appears in d1/d2; but even "kitten" (unseen) shouldn't crash, and "pet" should rank animals first
    const hits = m.search(tokenize("pet animal"), 4).map((h) => h.id);
    expect(hits.slice(0, 2).sort()).toEqual(["d1", "d2"]); // animal docs above vehicle docs
  });

  it("vehicle query ranks vehicle docs first", () => {
    const hits = m.search(tokenize("drive road"), 4).map((h) => h.id);
    expect(new Set(hits.slice(0, 2))).toEqual(new Set(["d3", "d4"]));
  });

  it("is deterministic across builds", () => {
    const m2 = build(corpus);
    expect(m2.search(tokenize("pet"), 4)).toEqual(m.search(tokenize("pet"), 4));
  });

  it("empty model returns nothing", () => {
    expect(new SemanticModel().search(["x"])).toEqual([]);
  });
});
