import { describe, it, expect } from "vitest";
import { reconcilePlan } from "../src/workspace/search-indexer";

describe("reconcilePlan (incremental index decision)", () => {
  it("indexes new and changed files, skips unchanged, removes deleted", () => {
    const stored = new Map([
      ["a.md", "100:10"], // unchanged
      ["b.pdf", "200:20"], // changed
      ["c.docx", "300:30"], // deleted (not in current)
    ]);
    const current = new Map([
      ["a.md", "100:10"],
      ["b.pdf", "999:99"], // new signature
      ["d.epub", "400:40"], // brand new
    ]);
    const plan = reconcilePlan(current, stored);
    expect(plan.index.sort()).toEqual(["b.pdf", "d.epub"]);
    expect(plan.remove).toEqual(["c.docx"]);
  });

  it("empty stored → index everything, remove nothing", () => {
    const plan = reconcilePlan(new Map([["x.md", "1:1"], ["y.pdf", "2:2"]]), new Map());
    expect(plan.index.sort()).toEqual(["x.md", "y.pdf"]);
    expect(plan.remove).toEqual([]);
  });

  it("nothing changed → no work", () => {
    const m = new Map([["x.md", "1:1"]]);
    const plan = reconcilePlan(new Map(m), new Map(m));
    expect(plan.index).toEqual([]);
    expect(plan.remove).toEqual([]);
  });
});
