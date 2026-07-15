import { describe, expect, it } from "vitest";
import { SearchIndex } from "../src/services/search/search-index";
import { mapAnnotation } from "../src/services/zotero/local-api-provider";
import {
  annotationToSearchDoc,
  itemToSearchDoc,
  zoteroSearchDocs,
  ZOTERO_DOC_PREFIX,
} from "../src/services/zotero/zotero-search-docs";
import type { ZoteroAnnotationRecord, ZoteroLibraryItem } from "../src/services/zotero/provider";

/**
 * Zotero search integration folds library items and annotations into the *same* index the vault uses. Two
 * things to verify: the documents are built with the right searchable text and boosted fields, and — the
 * real proof — that a query against a live SearchIndex actually finds them, ranked by the same model.
 */

function item(over: Partial<ZoteroLibraryItem>): ZoteroLibraryItem {
  return {
    key: "K", libraryId: 0, version: 1, itemType: "journalArticle", title: "T", creators: "A", year: "2020",
    publication: "P", doi: "", url: "", tags: [], collections: [], dateAdded: "2020-01-01", dateModified: "2020-06-01",
    citeKey: "", attachmentKeys: [], extra: {}, ...over,
  };
}

describe("itemToSearchDoc", () => {
  it("builds a document with title/tags boosted and the rest as body text", () => {
    const doc = itemToSearchDoc(item({
      key: "A1", title: "Attention Is All You Need", creators: "Vaswani", publication: "NeurIPS",
      tags: ["transformers", "nlp"], extra: { abstract: "We propose the Transformer." },
    }));
    expect(doc.source).toBe("zotero");
    expect(doc.id.startsWith(ZOTERO_DOC_PREFIX)).toBe(true);
    expect(doc.fields?.["title"]).toBe("Attention Is All You Need");
    expect(doc.fields?.["tag"]).toBe("transformers nlp");
    // Body carries the searchable free text.
    expect(doc.text).toContain("Vaswani");
    expect(doc.text).toContain("Transformer");
    // The key is retrievable for opening the item in Zotero.
    expect(doc.meta?.["zoteroKey"]).toBe("A1");
    // The header names the paper (the bug fix): title first, then a readable type — not a bare type.
    expect(doc.location).toBe("Attention Is All You Need · Journal article");
    expect(doc.meta?.["title"]).toBe("Attention Is All You Need");
  });

  it("carries the modified time as the recency signal", () => {
    const doc = itemToSearchDoc(item({ dateModified: "2021-05-01T00:00:00Z" }));
    expect(typeof doc.meta?.["mtime"]).toBe("number");
  });
});

describe("annotationToSearchDoc", () => {
  it("indexes the quoted text and the comment together", () => {
    const a: ZoteroAnnotationRecord = { key: "AN1", parentKey: "ATT1", type: "highlight", text: "the key insight", comment: "important for chapter 3", pageLabel: "12" };
    const doc = annotationToSearchDoc(a);
    expect(doc.source).toBe("zotero-annotation");
    expect(doc.text).toContain("the key insight");
    expect(doc.text).toContain("important for chapter 3");
    // With no resolved parent, the header still names the page, prefixed generically (not a bare number).
    expect(doc.location).toBe("Annotation · p. 12");
    // The parent key lets a hit open the underlying item in Zotero.
    expect(doc.meta?.["parentKey"]).toBe("ATT1");
  });

  it("names the source paper in the header when the parent title is known", () => {
    const a: ZoteroAnnotationRecord = { key: "AN2", parentKey: "P1", type: "highlight", text: "quoted", comment: "", pageLabel: "5" };
    const doc = annotationToSearchDoc(a, "Attention Is All You Need");
    expect(doc.location).toBe("Attention Is All You Need · p. 5");
    expect(doc.meta?.["title"]).toBe("Attention Is All You Need");
  });

  it("falls back to a readable type label when there's no page number", () => {
    const a: ZoteroAnnotationRecord = { key: "AN3", parentKey: "P1", type: "underline", text: "x", comment: "", pageLabel: "" };
    expect(annotationToSearchDoc(a).location).toBe("Annotation · Underline");
  });
});

describe("mapAnnotation — parsing the Zotero annotation item", () => {
  it("maps a highlight annotation item", () => {
    const rec = mapAnnotation({ key: "AN1", data: { key: "AN1", itemType: "annotation", annotationType: "highlight", annotationText: "quoted", annotationComment: "note", parentItem: "ATT1", annotationPageLabel: "5" } });
    expect(rec).toMatchObject({ key: "AN1", parentKey: "ATT1", type: "highlight", text: "quoted", comment: "note", pageLabel: "5" });
  });

  it("skips a non-annotation item, or one with no key", () => {
    expect(mapAnnotation({ key: "X", data: { key: "X", itemType: "journalArticle" } })).toBeNull();
    expect(mapAnnotation({ data: { itemType: "annotation", annotationText: "x" } })).toBeNull();
  });

  it("skips an annotation with neither text nor comment (nothing to search)", () => {
    expect(mapAnnotation({ key: "AN", data: { key: "AN", itemType: "annotation", annotationType: "ink" } })).toBeNull();
  });
});

describe("Zotero documents are findable through the real search index", () => {
  it("a query finds a Zotero item by its title, and an annotation by its words", () => {
    const index = new SearchIndex();
    const items = [
      item({ key: "A1", title: "Attention Is All You Need", tags: ["transformers"], extra: { abstract: "self-attention mechanism" } }),
      item({ key: "B2", title: "Convolutional Networks", extra: { abstract: "image classification" } }),
    ];
    const annotations: ZoteroAnnotationRecord[] = [
      { key: "AN1", parentKey: "A1", type: "highlight", text: "positional encoding is crucial", comment: "", pageLabel: "3" },
    ];
    for (const doc of zoteroSearchDocs(items, annotations)) index.add(doc);

    // Item found by title.
    const byTitle = index.search("attention", { matchMode: "any" });
    expect(byTitle.some((r) => r.source === "zotero" && r.meta?.["zoteroKey"] === "A1")).toBe(true);

    // Annotation found by the words highlighted — the whole point of indexing annotations.
    const byAnnotation = index.search("positional encoding", { matchMode: "any" });
    expect(byAnnotation.some((r) => r.source === "zotero-annotation")).toBe(true);

    // A term only in the abstract still matches its item.
    const byAbstract = index.search("classification", { matchMode: "any" });
    expect(byAbstract.some((r) => r.meta?.["zoteroKey"] === "B2")).toBe(true);
  });

  it("a title match outranks a body-only match, because the title field is boosted", () => {
    const index = new SearchIndex();
    // One item has the term in its title; another only in its abstract.
    for (const doc of zoteroSearchDocs(
      [
        item({ key: "TITLE", title: "Bandit Algorithms" }),
        item({ key: "BODY", title: "Something Else", extra: { abstract: "a survey of bandit methods" } }),
      ],
      [],
    )) index.add(doc);
    const results = index.search("bandit", { matchMode: "any", fieldBoosts: { title: 3, tag: 1.6 } });
    expect(results[0]!.meta?.["zoteroKey"]).toBe("TITLE");
  });

  it("zoteroSearchDocs names an annotation's parent paper when the parent is a fetched item", () => {
    const docs = zoteroSearchDocs(
      [item({ key: "P1", title: "Deep Learning" })],
      [{ key: "AN1", parentKey: "P1", type: "highlight", text: "backprop", comment: "", pageLabel: "9" }],
    );
    const ann = docs.find((d) => d.source === "zotero-annotation");
    expect(ann?.location).toBe("Deep Learning · p. 9");
  });
});
