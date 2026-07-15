import { describe, expect, it } from "vitest";
import { DataService } from "../src/services/data-service";
import { createProfile, DEFAULT_SETTINGS } from "../src/services/profile/profile";
import { ExtractorRegistry, createDefaultColumnTypeRegistry, tableExtractor, getField } from "../src/domain/index";
import { buildKanbanBoard } from "../src/views/kanban/board";
import { ReadOnlyZoteroBackend, type ZoteroLibraryItem, type ZoteroProvider } from "../src/services/zotero/provider";
import { FakeVaultGateway } from "./_helpers";

/**
 * The payoff test: a Zotero-scoped profile is not a bespoke panel, it is a first-class data source. Once
 * the provider's items become rows, the *entire* engine — filtering, sorting, and every layout — treats
 * them like any other source, with no Zotero-specific code downstream. This drives a real DataService with
 * a fake Zotero provider (no live Zotero needed) and proves rows flow all the way through the pipeline.
 */

function item(over: Partial<ZoteroLibraryItem>): ZoteroLibraryItem {
  return {
    key: "K", libraryId: 0, version: 1, itemType: "journalArticle", title: "T", creators: "A", year: "2020",
    publication: "P", doi: "", url: "", tags: [], collections: [], dateAdded: "2020-01-01", dateModified: "2020-01-01",
    citeKey: "", attachmentKeys: [], extra: {}, ...over,
  };
}

/** A fake provider returning a fixed library — the seam that lets us test the pipeline offline. */
function fakeProvider(items: ZoteroLibraryItem[]): ZoteroProvider {
  return {
    ping: () => Promise.resolve(true),
    listCollections: () => Promise.resolve([]),
    listItems: () => Promise.resolve(items),
    getItem: (k) => Promise.resolve(items.find((i) => i.key === k) ?? null),
    writes: new ReadOnlyZoteroBackend(),
  };
}

function serviceWith(items: ZoteroLibraryItem[], provider: ZoteroProvider | null = fakeProvider(items)): DataService {
  return new DataService({
    gateway: new FakeVaultGateway(),
    registry: createDefaultColumnTypeRegistry(),
    extractors: new ExtractorRegistry().register(tableExtractor),
    getSettings: () => DEFAULT_SETTINGS,
    zoteroProvider: () => provider,
  });
}

const LIBRARY = [
  item({ key: "A1", title: "Attention Is All You Need", itemType: "journalArticle", year: "2017", tags: ["ml", "nlp"] }),
  item({ key: "B2", title: "A Survey of Bandits", itemType: "book", year: "2019", tags: ["ml"] }),
  item({ key: "C3", title: "Old Paper", itemType: "journalArticle", year: "1998", tags: ["history"] }),
];

const zoteroProfile = (over = {}) =>
  createProfile({
    name: "Zotero",
    scope: { mode: "zotero", folders: [], includeSubfolders: false },
    extractors: ["zotero-library"],
    columns: [
      { name: "Title", type: "text", role: "title" },
      { name: "Type", type: "text", role: "status" },
      { name: "Year", type: "text" },
      { name: "Tags", type: "tags", role: "tags" },
    ],
    ...over,
  });

describe("Zotero library flows through the full view engine", () => {
  it("a Zotero-scoped profile produces rows from the provider, not from files", async () => {
    const service = serviceWith(LIBRARY);
    const { rows } = await service.query(zoteroProfile());
    expect(rows).toHaveLength(3);
    expect(rows.map((r) => getField(r, "Title"))).toContain("Attention Is All You Need");
    service.dispose();
  });

  it("filters apply to Zotero rows exactly like any other source", async () => {
    const service = serviceWith(LIBRARY);
    const profile = zoteroProfile({
      filter: { combinator: "and", conditions: [{ field: "Type", operator: "equals", value: "book" }], groups: [] },
    });
    const { rows } = await service.query(profile);
    expect(rows).toHaveLength(1);
    expect(getField(rows[0]!, "Title")).toBe("A Survey of Bandits");
    service.dispose();
  });

  it("sorting applies to Zotero rows", async () => {
    const service = serviceWith(LIBRARY);
    const profile = zoteroProfile({ sort: [{ field: "Year", direction: "asc" }] });
    const { rows } = await service.query(profile);
    expect(rows.map((r) => getField(r, "Year"))).toEqual(["1998", "2017", "2019"]);
    service.dispose();
  });

  it("the kanban layout groups Zotero rows by a column, same as file rows", async () => {
    const service = serviceWith(LIBRARY);
    const { rows } = await service.query(zoteroProfile());
    const board = buildKanbanBoard(rows, "Type");
    const columnKeys = board.columns.map((c) => c.key).sort();
    expect(columnKeys).toContain("book");
    expect(columnKeys).toContain("journalArticle");
    service.dispose();
  });

  it("every Zotero row is read-only today (the write seam, end to end through the engine)", async () => {
    const service = serviceWith(LIBRARY);
    const { rows } = await service.query(zoteroProfile());
    // The rows that come out of the full pipeline still carry the read-only marking — editing is blocked
    // until Zotero supports writes, via the same mechanism as an Excel formula cell.
    for (const row of rows) {
      expect(row.provenance.readOnlyFields?.length ?? 0).toBeGreaterThan(0);
      expect(row.provenance.extractor).toBe("zotero-library");
    }
    service.dispose();
  });

  it("degrades to an empty dataset when no provider is configured — never throws", async () => {
    const service = serviceWith([], null);
    const { rows } = await service.query(zoteroProfile());
    expect(rows).toEqual([]);
    service.dispose();
  });

  it("degrades to empty (not a crash) when the provider throws", async () => {
    const throwing: ZoteroProvider = {
      ping: () => Promise.resolve(true),
      listCollections: () => Promise.resolve([]),
      listItems: () => Promise.reject(new Error("Zotero unreachable")),
      getItem: () => Promise.resolve(null),
      writes: new ReadOnlyZoteroBackend(),
    };
    const service = serviceWith([], throwing);
    const { rows } = await service.query(zoteroProfile());
    expect(rows).toEqual([]);
    service.dispose();
  });
});
