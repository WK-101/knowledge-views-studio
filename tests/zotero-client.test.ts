import { describe, it, expect } from "vitest";
import {
  readZoteroItem,
  orderCollections,
  webpageItem,
  sessionId,
  zoteroSelectLink,
} from "../extension/src/lib/zotero-client";

describe("zotero · reading search results", () => {
  it("reads an ordinary item with its identifiers", () => {
    const hit = readZoteroItem({
      key: "ABCD1234",
      data: { itemType: "journalArticle", title: "A Paper", url: "https://x/a", DOI: "10.1/x" },
    });
    expect(hit).toEqual({ key: "ABCD1234", title: "A Paper", itemType: "journalArticle", url: "https://x/a", doi: "10.1/x" });
  });

  it("turns an annotation into a hit whose excerpt is the highlighted text", () => {
    const hit = readZoteroItem({
      key: "K1",
      data: { itemType: "annotation", annotationText: "The <b>important</b>   claim." },
    });
    expect(hit?.excerpt).toBe("The important claim.");
    expect(hit?.title).toBe("The important claim.");
  });

  it("strips html from notes rather than displaying markup", () => {
    const hit = readZoteroItem({ key: "K2", data: { itemType: "note", note: "<p>My <i>note</i> text</p>" } });
    expect(hit?.excerpt).toBe("My note text");
  });

  it("drops attachments, empty notes, and malformed entries", () => {
    expect(readZoteroItem({ key: "K", data: { itemType: "attachment", title: "file.pdf" } })).toBeNull();
    expect(readZoteroItem({ key: "K", data: { itemType: "note", note: "  " } })).toBeNull();
    expect(readZoteroItem(null)).toBeNull();
    expect(readZoteroItem({ data: { itemType: "book" } })).toBeNull();
  });
});

describe("zotero · collections in display order", () => {
  it("orders children under their parents, with depth for indenting", () => {
    const ordered = orderCollections([
      { key: "B", name: "Beta", parent: false },
      { key: "A", name: "Alpha", parent: false },
      { key: "A1", name: "Inside Alpha", parent: "A" },
    ]);
    expect(ordered.map((c) => c.name)).toEqual(["Alpha", "Inside Alpha", "Beta"]);
    expect(ordered[1]?.depth).toBe(1);
  });
});

describe("zotero · what gets saved", () => {
  it("builds the webpage item the connector protocol expects", () => {
    const item = webpageItem({ title: "T", url: "https://x/a", doi: "10.1/x", abstract: "About it." });
    expect(item["itemType"]).toBe("webpage");
    expect(item["extra"]).toBe("DOI: 10.1/x");
    expect(item["abstractNote"]).toBe("About it.");
    expect(item["accessDate"]).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("omits what it doesn't have rather than sending empty fields", () => {
    const item = webpageItem({ title: "T", url: "https://x/a" });
    expect("extra" in item).toBe(false);
    expect("abstractNote" in item).toBe(false);
  });

  it("makes session ids in the connector's shape", () => {
    expect(sessionId()).toMatch(/^[a-z0-9]{8}$/);
    expect(sessionId()).not.toBe(sessionId());
  });

  it("links into Zotero by item key", () => {
    expect(zoteroSelectLink("ABCD1234")).toBe("zotero://select/library/items/ABCD1234");
  });
});
