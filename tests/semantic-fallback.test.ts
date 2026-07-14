import { describe, it, expect } from "vitest";
import { SemanticModel } from "../src/services/search/semantic";
import { tokenize } from "../src/services/search/tokenize";
import { VectorIndex, cosine, normalize, hasSignal } from "../src/services/search/vectors";

function build(corpus: string[]): SemanticModel {
  const m = new SemanticModel();
  const toks = corpus.map((t, i) => ({ id: `d${i}`, tokens: tokenize(t) }));
  for (const d of toks) m.observe(d.tokens);
  for (const d of toks) m.addDocVector(d.id, d.tokens);
  return m;
}

describe("semantic search knows when it has nothing to say", () => {
  const m = build([
    "my car needs new tyres and an oil change before the road trip",
    "quarterly budget review with the finance team on Thursday",
  ]);

  it("reports that it cannot answer a query made of unknown words", () => {
    // This is the honest signal that lets the caller fall back to keyword search instead of showing
    // an empty list and implying the note does not exist.
    expect(m.canAnswer(tokenize("automobile"))).toBe(false);
    expect(m.canAnswer(tokenize("car"))).toBe(true);
    expect(m.canAnswer(tokenize("automobile car"))).toBe(true); // one known word is enough
  });
});

describe("similarTo — the primitive behind Related notes", () => {
  it("ranks the topically closest document first, and never the note itself", () => {
    const m = build([
      "the transformer model uses self attention over token sequences",
      "attention layers let the model weigh token relationships",
      "sourdough starter needs flour water and time to ferment",
    ]);
    const hits = m.similarTo("d0", 5);
    expect(hits.map((h) => h.id)).not.toContain("d0"); // never itself
    expect(hits[0]!.id).toBe("d1"); // the other ML note, not the baking one
  });
  it("returns nothing for an unknown document", () => {
    expect(build(["a"]).similarTo("nope")).toEqual([]);
  });
});

describe("VectorIndex (shared by both semantic engines)", () => {
  const v = (...n: number[]) => normalize(Float32Array.from(n));

  it("cosine is 1 for identical, 0 for orthogonal", () => {
    expect(cosine(v(1, 0), v(1, 0))).toBeCloseTo(1);
    expect(cosine(v(1, 0), v(0, 1))).toBeCloseTo(0);
  });
  it("an all-zero query can only return nothing — so we detect it", () => {
    expect(hasSignal(new Float32Array([0, 0, 0]))).toBe(false);
    expect(hasSignal(new Float32Array([0, 0.1, 0]))).toBe(true);
  });
  it("ranks by similarity and excludes what it's told to", () => {
    const idx = new VectorIndex();
    idx.add("a", v(1, 0));
    idx.add("b", v(0.9, 0.1));
    idx.add("c", v(0, 1));
    expect(idx.search(v(1, 0), 3).map((h) => h.id)).toEqual(["a", "b"]);
    expect(idx.similarTo("a", 3).map((h) => h.id)).toEqual(["b"]); // never itself; c is orthogonal
  });
  it("an unseen query vector returns nothing rather than noise", () => {
    const idx = new VectorIndex();
    idx.add("a", v(1, 0));
    expect(idx.search(new Float32Array([0, 0]), 3)).toEqual([]);
  });
  it("mean() gives a note one vector from its several chunks", () => {
    const mean = VectorIndex.mean([v(1, 0), v(0, 1)]);
    expect(cosine(mean, v(1, 1))).toBeCloseTo(1);
  });
  it("survives a snapshot round-trip", () => {
    const idx = new VectorIndex();
    idx.add("a", v(1, 0));
    const back = VectorIndex.fromSnapshot(idx.toSnapshot());
    expect(back.size).toBe(1);
    expect(back.search(v(1, 0), 1)[0]!.id).toBe("a");
  });
});
