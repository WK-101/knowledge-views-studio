import { describe, it, expect } from "vitest";
import { encodeIndex, decodeIndex, formatBytes } from "../src/services/search/index-codec";

describe("the index survives a round-trip through bytes", () => {
  it("typed arrays come back as typed arrays, not as objects with numeric keys", () => {
    const value = { vec: Float32Array.from([0.5, -0.25, 1]), counts: Int32Array.from([1, 2, 3]) };
    const back = decodeIndex(encodeIndex(value)) as typeof value;
    expect(back.vec).toBeInstanceOf(Float32Array);
    expect(back.counts).toBeInstanceOf(Int32Array);
    expect([...back.vec]).toEqual([0.5, -0.25, 1]);
    expect([...back.counts]).toEqual([1, 2, 3]);
  });

  it("nested and repeated typed arrays keep their identity and order", () => {
    const value = {
      docs: [{ id: "a", vec: Float32Array.from([1, 2]) }, { id: "b", vec: Float32Array.from([3, 4]) }],
      meta: { nested: { deep: Float32Array.from([9]) } },
    };
    const back = decodeIndex(encodeIndex(value)) as typeof value;
    expect([...back.docs[0]!.vec]).toEqual([1, 2]);
    expect([...back.docs[1]!.vec]).toEqual([3, 4]);
    expect([...back.meta.nested.deep]).toEqual([9]);
    expect(back.docs[1]!.id).toBe("b");
  });

  it("ordinary JSON is untouched", () => {
    const value = { s: "hi", n: 42, b: true, nil: null, arr: [1, "x", false], map: [["k", "v"]] };
    expect(decodeIndex(encodeIndex(value))).toEqual(value);
  });

  it("an empty typed array round-trips", () => {
    const back = decodeIndex(encodeIndex({ v: new Float32Array(0) })) as { v: Float32Array };
    expect(back.v).toBeInstanceOf(Float32Array);
    expect(back.v.length).toBe(0);
  });

  it("a big vector set survives exactly — this is the actual payload", () => {
    const docs = Array.from({ length: 200 }, (_, i) => ({
      id: `note:${i}.md`,
      vec: Float32Array.from({ length: 64 }, (_, j) => Math.sin(i + j)),
    }));
    const back = decodeIndex(encodeIndex({ docs })) as { docs: typeof docs };
    expect(back.docs).toHaveLength(200);
    for (let i = 0; i < 200; i++) {
      expect([...back.docs[i]!.vec]).toEqual([...docs[i]!.vec]);
    }
  });

  it("it actually compresses — the point of putting it in a synced vault", () => {
    // Postings are runs of small ints; vectors are dense floats. Both compress.
    const value = { postings: Array.from({ length: 5000 }, (_, i) => i % 50) };
    const raw = JSON.stringify(value).length;
    const encoded = encodeIndex(value).byteLength;
    expect(encoded).toBeLessThan(raw / 2);
  });
});

describe("a broken or foreign file is refused, not guessed at", () => {
  it("random bytes decode to undefined rather than throwing", () => {
    expect(decodeIndex(new Uint8Array([1, 2, 3, 4, 5]))).toBeUndefined();
  });

  it("a truncated file — the classic half-finished sync — is refused", () => {
    const full = encodeIndex({ vec: Float32Array.from([1, 2, 3]) });
    expect(decodeIndex(full.subarray(0, Math.floor(full.length / 2)))).toBeUndefined();
  });

  it("an empty file is refused", () => {
    expect(decodeIndex(new Uint8Array(0))).toBeUndefined();
  });
});

describe("formatBytes tells the user what they're putting in their vault", () => {
  it("scales", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
  });
});

describe("a REAL index survives being written to a file and read back", () => {
  it("keyword index: same query, same results, in the same order", async () => {
    const { SearchIndex } = await import("../src/services/search/search-index");
    const idx = new SearchIndex();
    for (let i = 0; i < 50; i++) {
      idx.add({
        id: `note:${i}.md`,
        text: `the transformer model number ${i} uses attention over tokens`,
        source: "note",
        meta: { path: `${i}.md` },
      });
    }
    const before = idx.search("transformer attention").map((r) => r.id);

    const payload = { main: { v: 1, snapshot: idx.toSnapshot(), sigs: [], idsByPath: [], builtAt: 0 } };
    const back = decodeIndex(encodeIndex(payload)) as typeof payload;
    const restored = SearchIndex.fromSnapshot(back.main.snapshot);

    expect(restored.search("transformer attention").map((r) => r.id)).toEqual(before);
  });

  it("semantic model: same ranking — which is the real test that Float32Array survived", async () => {
    const { SemanticModel } = await import("../src/services/search/semantic");
    const { tokenize } = await import("../src/services/search/tokenize");
    const m = new SemanticModel();
    const corpus = ["the cat is a pet you feed the cat", "the dog is a pet you feed the dog", "the car has an engine you drive"];
    const toks = corpus.map((t, i) => ({ id: `d${i}`, tokens: tokenize(t) }));
    for (const d of toks) m.observe(d.tokens);
    for (const d of toks) m.addDocVector(d.id, d.tokens);
    const before = m.search(tokenize("pet"), 3);

    const back = decodeIndex(encodeIndex({ semantic: m.toSnapshot() })) as { semantic: never };
    const restored = SemanticModel.fromSnapshot(back.semantic);

    expect(restored.search(tokenize("pet"), 3)).toEqual(before);
  });
});
