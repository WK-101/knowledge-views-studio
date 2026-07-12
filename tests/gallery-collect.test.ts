import { describe, it, expect } from "vitest";
import { collectGalleryImages } from "../src/views/gallery/collect";
import { makeRow } from "./_helpers";

const col = (name: string) => ({ name });

describe("collectGalleryImages", () => {
  it("extracts multiple images from one cell and across columns", () => {
    const rows = [
      makeRow({ Title: "A", Photos: "![[a.png]] ![[b.png]]", Cover: "![](https://x/c.jpg)" }),
      makeRow({ Title: "B", Photos: "", Cover: "![[d.png]]" }),
    ];
    const cols = [col("Title"), col("Photos"), col("Cover")];
    const items = collectGalleryImages(rows, cols, 100);
    expect(items.map((i) => i.embed)).toEqual(["![[a.png]]", "![[b.png]]", "![](https://x/c.jpg)", "![[d.png]]"]);
    // each item knows its source column + row
    expect(items[0]!.column.name).toBe("Photos");
    expect(items[3]!.column.name).toBe("Cover");
  });

  it("scans only the selected column when narrowed", () => {
    const rows = [makeRow({ Photos: "![[a.png]]", Cover: "![[b.png]]" })];
    const items = collectGalleryImages(rows, [col("Cover")], 100);
    expect(items.map((i) => i.embed)).toEqual(["![[b.png]]"]);
  });

  it("respects the limit", () => {
    const rows = [makeRow({ Photos: "![[a.png]] ![[b.png]] ![[c.png]]" })];
    expect(collectGalleryImages(rows, [col("Photos")], 2)).toHaveLength(2);
  });

  it("ignores cells with no images", () => {
    const rows = [makeRow({ Title: "no pics here", Note: "just text" })];
    expect(collectGalleryImages(rows, [col("Title"), col("Note")], 100)).toEqual([]);
  });
});
