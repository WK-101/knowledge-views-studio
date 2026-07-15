import { describe, expect, it } from "vitest";
import { buildCollectionChoices } from "../src/workspace/zotero-collection-modal";
import type { ZoteroCollection } from "../src/services/zotero/provider";

/**
 * The collection picker turns Zotero's flat collection list (each with a parentKey) into an indented tree,
 * so a user can scope a dashboard to a collection. The ordering and indentation are the logic worth
 * pinning: parents must precede their children, siblings sort by name, and the whole-library option leads.
 */

function coll(key: string, name: string, parentKey: string | null, itemCount = 0): ZoteroCollection {
  return { key, name, parentKey, itemCount };
}

describe("buildCollectionChoices", () => {
  it("always offers 'Whole library' first, with a null key", () => {
    const choices = buildCollectionChoices([]);
    expect(choices).toHaveLength(1);
    expect(choices[0]).toMatchObject({ key: null, name: "Whole library" });
  });

  it("lists top-level collections with their item counts", () => {
    const choices = buildCollectionChoices([coll("A", "Apples", null, 5), coll("B", "Bananas", null, 3)]);
    expect(choices.map((c) => c.label)).toEqual(["Whole library", "Apples (5)", "Bananas (3)"]);
  });

  it("sorts siblings by name", () => {
    const choices = buildCollectionChoices([coll("Z", "Zebra", null), coll("A", "Aardvark", null)]);
    expect(choices.slice(1).map((c) => c.name)).toEqual(["Aardvark", "Zebra"]);
  });

  it("nests children under parents, indented, in tree order", () => {
    // Thesis > (Methods, Results > Figures)
    const choices = buildCollectionChoices([
      coll("T", "Thesis", null, 10),
      coll("M", "Methods", "T", 4),
      coll("R", "Results", "T", 6),
      coll("F", "Figures", "R", 2),
    ]);
    // Parents precede children; depth shows as leading spaces.
    expect(choices.map((c) => c.label)).toEqual([
      "Whole library",
      "Thesis (10)",
      "  Methods (4)",
      "  Results (6)",
      "    Figures (2)",
    ]);
    // The clean name (for the dashboard title) is un-indented.
    const figures = choices.find((c) => c.key === "F");
    expect(figures?.name).toBe("Figures");
  });

  it("keeps the flat key mapping intact for scoping", () => {
    const choices = buildCollectionChoices([coll("K1", "One", null), coll("K2", "Two", "K1")]);
    expect(choices.find((c) => c.name === "Two")?.key).toBe("K2");
  });
});
