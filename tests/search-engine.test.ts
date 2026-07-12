import { describe, it, expect } from "vitest";
import { tokenize, foldText } from "../src/services/search/tokenize";
import { parseQuery, scoringTerms } from "../src/services/search/query";
import { SearchIndex, type IndexDoc } from "../src/services/search/search-index";

describe("tokenizer", () => {
  it("folds accents + case and splits on non-alphanumerics", () => {
    expect(tokenize("Résumé: naïve CAFÉ!")).toEqual(["resume", "naive", "cafe"]);
  });
  it("keeps numbers and treats punctuation as boundaries", () => {
    expect(tokenize("GPT-4 scored 92.5% (state-of-the-art)")).toEqual(["gpt", "4", "scored", "92", "5", "state", "of", "the", "art"]);
  });
  it("foldText is idempotent on ascii", () => {
    expect(foldText("Hello")).toBe("hello");
  });
});

describe("query parser", () => {
  it("parses implicit AND", () => {
    expect(parseQuery("neural networks")).toEqual({ type: "and", children: [{ type: "term", value: "neural" }, { type: "term", value: "networks" }] });
  });
  it("parses OR with lower precedence than AND", () => {
    const ast = parseQuery("a b OR c");
    expect(ast.type).toBe("or"); // (a AND b) OR c
    expect((ast as unknown as { children: { type: string }[] }).children[0]!.type).toBe("and");
  });
  it("parses phrases, exclusion and fields", () => {
    expect(parseQuery('"deep learning"')).toEqual({ type: "phrase", terms: ["deep", "learning"] });
    expect(parseQuery("cats -dogs")).toEqual({ type: "and", children: [{ type: "term", value: "cats" }, { type: "not", child: { type: "term", value: "dogs" } }] });
    expect(parseQuery("author:hinton")).toEqual({ type: "term", field: "author", value: "hinton" });
  });
  it("honours defaultOp = or", () => {
    expect(parseQuery("a b", { defaultOp: "or" }).type).toBe("or");
  });
  it("collects positive scoring terms, excluding NOT", () => {
    expect(scoringTerms(parseQuery("cats -dogs title:fish")).sort()).toEqual(["cats", "title\u0000fish"]);
  });
  it("never throws on malformed input", () => {
    expect(() => parseQuery('((a OR "unterminated')).not.toThrow();
    expect(parseQuery("   ").type).toBe("empty");
  });
});

function build(docs: IndexDoc[]): SearchIndex {
  const idx = new SearchIndex();
  for (const d of docs) idx.add(d);
  return idx;
}

describe("search + BM25 ranking", () => {
  const idx = build([
    { id: "d1", text: "the cat sat on the mat", source: "note" },
    { id: "d2", text: "cats and dogs are common pets, the cat is a cat", source: "note" },
    { id: "d3", text: "a long document about astrophysics and cosmology and galaxies and stars and nebulae", source: "pdf", format: "pdf" },
  ]);

  it("ranks the doc with higher term frequency first", () => {
    const r = idx.search("cat");
    expect(r[0]!.id).toBe("d2"); // "cat" x3 vs x1
  });
  it("AND requires all terms", () => {
    expect(idx.search("cat mat").map((x) => x.id)).toEqual(["d1"]);
    expect(idx.search("cat dogs").map((x) => x.id)).toEqual(["d2"]);
  });
  it("OR unions", () => {
    expect(new Set(idx.search("mat OR dogs").map((x) => x.id))).toEqual(new Set(["d1", "d2"]));
  });
  it("NOT excludes", () => {
    expect(idx.search("cat -dogs").map((x) => x.id)).toEqual(["d1"]);
  });
  it("rarer terms score higher (idf)", () => {
    // "astrophysics" is rare (1 doc) → strong hit
    expect(idx.search("astrophysics")[0]!.id).toBe("d3");
  });
});

describe("phrases + fields + filters", () => {
  const idx = build([
    { id: "p1", text: "we study deep learning models", source: "note", fields: { title: "A Survey" } },
    { id: "p2", text: "learning is deep but not deep learning here... deep", source: "note" },
    { id: "p3", text: "attention mechanisms", source: "pdf", format: "pdf", fields: { author: "Vaswani" } },
  ]);

  it("phrase requires adjacency in order", () => {
    // both p1 (deep@2,learning@3) and p2 (deep@5,learning@6) have the phrase adjacent
    expect(new Set(idx.search('"deep learning"').map((x) => x.id))).toEqual(new Set(["p1", "p2"]));
    // but a phrase whose words never sit adjacent finds nothing
    expect(idx.search('"learning deep"')).toEqual([]); // never adjacent in that order
  });
  it("field scoping matches the field", () => {
    expect(idx.search("author:vaswani").map((x) => x.id)).toEqual(["p3"]);
    expect(idx.search("author:hinton")).toEqual([]);
  });
  it("plain query still finds field text", () => {
    expect(idx.search("vaswani").map((x) => x.id)).toEqual(["p3"]);
  });
  it("source + format filters restrict results", () => {
    expect(idx.search("deep", { sources: new Set(["pdf"]) })).toEqual([]);
    expect(idx.search("attention", { formats: new Set(["pdf"]) }).map((x) => x.id)).toEqual(["p3"]);
  });
  it("source boosts reorder results", () => {
    const i2 = build([
      { id: "a", text: "quantum quantum", source: "pdf" },
      { id: "b", text: "quantum", source: "note" },
    ]);
    // Without boost, "a" (tf 2) wins; boosting notes 10x flips it.
    expect(i2.search("quantum")[0]!.id).toBe("a");
    expect(i2.search("quantum", { boosts: { note: 10 } })[0]!.id).toBe("b");
  });
});

describe("incremental add / remove / replace", () => {
  it("removes docs from results and updates stats", () => {
    const idx = build([
      { id: "x", text: "alpha beta", source: "note" },
      { id: "y", text: "alpha gamma", source: "note" },
    ]);
    expect(idx.search("alpha").length).toBe(2);
    idx.remove("x");
    expect(idx.search("alpha").map((r) => r.id)).toEqual(["y"]);
    expect(idx.size).toBe(1);
  });
  it("replacing a doc reindexes its text", () => {
    const idx = build([{ id: "z", text: "old content", source: "note" }]);
    idx.add({ id: "z", text: "new material", source: "note" });
    expect(idx.search("old")).toEqual([]);
    expect(idx.search("material").map((r) => r.id)).toEqual(["z"]);
    expect(idx.size).toBe(1);
  });
});

describe("compaction + serialization", () => {
  it("compact() reclaims tombstones but preserves results", () => {
    const idx = build([
      { id: "1", text: "keep this alpha", source: "note" },
      { id: "2", text: "drop this alpha", source: "note" },
      { id: "3", text: "keep this beta alpha", source: "note" },
    ]);
    idx.remove("2");
    expect(idx.wastedFraction).toBeCloseTo(1 / 3, 5);
    idx.compact();
    expect(idx.wastedFraction).toBe(0);
    expect(idx.search("alpha").map((r) => r.id).sort()).toEqual(["1", "3"]);
    expect(idx.search("beta").map((r) => r.id)).toEqual(["3"]);
  });

  it("round-trips through a snapshot (persistence)", () => {
    const idx = build([
      { id: "s1", text: "the quick brown fox", source: "note", fields: { title: "Fox" } },
      { id: "s2", text: "lazy dog sleeps", source: "pdf", format: "pdf", location: "p.2" },
    ]);
    const restored = SearchIndex.fromSnapshot(structuredClone(idx.toSnapshot()));
    expect(restored.size).toBe(2);
    expect(restored.search("fox")[0]!.id).toBe("s1");
    expect(restored.search("dog")[0]!.location).toBe("p.2");
    expect(restored.search("title:fox").map((r) => r.id)).toEqual(["s1"]);
  });
});

describe("fuzzy / prefix matching", () => {
  const idx = build([
    { id: "a", text: "neural networks and deep learning", source: "note" },
    { id: "b", text: "the neuron fires", source: "note" },
    { id: "c", text: "unrelated content", source: "note" },
  ]);
  it("exact search does not partial-match", () => {
    expect(idx.search("neura")).toEqual([]); // no term "neura"
  });
  it("fuzzy expands a partial term to prefix matches", () => {
    const ids = new Set(idx.search("neur", { fuzzy: true }).map((r) => r.id));
    expect(ids).toEqual(new Set(["a", "b"])); // neural + neuron
  });
  it("fuzzy still ranks by relevance", () => {
    expect(idx.search("learn", { fuzzy: true }).map((r) => r.id)).toEqual(["a"]); // learning
  });
});

describe("folder scoping", () => {
  const idx = build([
    { id: "1", text: "quantum entanglement", source: "note", meta: { path: "Physics/quantum.md" } },
    { id: "2", text: "quantum computing basics", source: "note", meta: { path: "CS/quantum.md" } },
    { id: "3", text: "quantum leap", source: "note", meta: { path: "Physics/Notes/leap.md" } },
  ]);
  it("restricts results to a folder (and its subfolders)", () => {
    const ids = new Set(idx.search("quantum", { folders: ["Physics"] }).map((r) => r.id));
    expect(ids).toEqual(new Set(["1", "3"])); // Physics/ and Physics/Notes/
  });
  it("supports multiple folders", () => {
    expect(idx.search("quantum", { folders: ["CS", "Physics/Notes"] }).map((r) => r.id).sort()).toEqual(["2", "3"]);
  });
  it("empty folder list = whole vault", () => {
    expect(idx.search("quantum", { folders: [] }).length).toBe(3);
  });
});

describe("edit-distance fuzzy (typos)", () => {
  const idx = build([
    { id: "a", text: "the algorithm is efficient", source: "note" },
    { id: "b", text: "unrelated text here", source: "note" },
  ]);
  it("matches a transposed/misspelled term", () => {
    expect(idx.search("algorthm")).toEqual([]); // exact: nothing
    expect(idx.search("algorthm", { fuzzy: true }).map((r) => r.id)).toEqual(["a"]); // 1 edit
    expect(idx.search("algorihtm", { fuzzy: true }).map((r) => r.id)).toEqual(["a"]); // transposition
  });
});

describe("regex search", () => {
  const idx = build([
    { id: "a", text: "neural neuron neurons", source: "note" },
    { id: "b", text: "network topology", source: "note" },
    { id: "c", text: "color colour", source: "note" },
  ]);
  it("matches terms by pattern", () => {
    expect(idx.search("/neuro.*/").map((r) => r.id)).toEqual(["a"]);
    expect(new Set(idx.search("/colou?r/").map((r) => r.id))).toEqual(new Set(["c"]));
  });
  it("invalid regex yields nothing (no throw)", () => {
    expect(() => idx.search("/[unclosed/")).not.toThrow();
  });
});

describe("field boosting (title/heading/tag outranks body)", () => {
  it("a title match outranks a body-only match", () => {
    const idx = build([
      { id: "body", text: "transformer ".repeat(8) + "models are discussed at length", source: "note" },
      { id: "titled", text: "a short note", source: "note", fields: { title: "Transformer" } },
    ]);
    // Despite "body" having far more occurrences, the title hit should win via the boost.
    expect(idx.search("transformer")[0]!.id).toBe("titled");
  });
  it("tag field is searchable and boosted", () => {
    const idx = build([
      { id: "x", text: "some content about pipelines", source: "note", fields: { tag: "devops" } },
      { id: "y", text: "devops mentioned once in body", source: "note" },
    ]);
    expect(idx.search("tag:devops").map((r) => r.id)).toEqual(["x"]);
    expect(idx.search("devops")[0]!.id).toBe("x"); // tagged note boosted above the body mention
  });
});
