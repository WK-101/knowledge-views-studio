import { describe, expect, it, vi } from "vitest";
import { LocalApiZoteroProvider, mapItem } from "../src/services/zotero/local-api-provider";
import {
  ReadOnlyZoteroBackend,
  type ZoteroFetcher,
  type ZoteroFieldEdit,
  type ZoteroWriteBackend,
  type ZoteroWriteResult,
} from "../src/services/zotero/provider";
import { zoteroItemToRow, zoteroItemsToRows, ZOTERO_COLUMNS } from "../src/services/zotero/zotero-rows";

/**
 * The Zotero library provider reads another program's live API and shapes it for our views. Two things
 * must be right: the defensive mapping of a JSON shape we don't control, and — the load-bearing part for
 * the future — the write seam, which is read-only today but must already route edits so that turning on
 * write support later is a swap, not a rewrite. Both are exercised here without a live Zotero.
 */

// A realistic Zotero local-API item envelope.
const ITEM_ENVELOPE = {
  key: "ABCD1234",
  version: 42,
  library: { id: 0, type: "user" },
  data: {
    key: "ABCD1234",
    version: 42,
    itemType: "journalArticle",
    title: "Attention Is All You Need",
    creators: [
      { creatorType: "author", firstName: "Ashish", lastName: "Vaswani" },
      { creatorType: "author", firstName: "Noam", lastName: "Shazeer" },
      { creatorType: "author", name: "The Transformer Team" },
    ],
    date: "2017-06-12",
    publicationTitle: "NeurIPS",
    DOI: "10.5555/3295222",
    url: "https://arxiv.org/abs/1706.03762",
    tags: [{ tag: "transformers" }, { tag: "attention" }],
    collections: ["COLL01"],
    dateAdded: "2020-01-01T00:00:00Z",
    dateModified: "2021-05-01T00:00:00Z",
    extra: "Citation Key: vaswani2017attention\nsomething: else",
    abstractNote: "We propose the Transformer.",
    volume: "30",
  },
};

describe("mapItem — defensive mapping of the local API JSON", () => {
  it("maps a full item envelope into a library item", () => {
    const item = mapItem(ITEM_ENVELOPE)!;
    expect(item).toMatchObject({
      key: "ABCD1234",
      libraryId: 0,
      version: 42,
      itemType: "journalArticle",
      title: "Attention Is All You Need",
      year: "2017",
      publication: "NeurIPS",
      doi: "10.5555/3295222",
      citeKey: "vaswani2017attention",
    });
    expect(item.tags).toEqual(["transformers", "attention"]);
    expect(item.collections).toEqual(["COLL01"]);
  });

  it("formats creators as a readable list, mixing name shapes", () => {
    // Vaswani, Shazeer, and The Transformer Team (last of {name} shape)
    expect(mapItem(ITEM_ENVELOPE)!.creators).toBe("Vaswani, Shazeer, and The Transformer Team");
  });

  it("keeps un-promoted fields in extra, and pulls the abstract in", () => {
    const item = mapItem(ITEM_ENVELOPE)!;
    expect(item.extra["volume"]).toBe("30");
    expect(item.extra["abstract"]).toBe("We propose the Transformer.");
  });

  it("returns null for an item with no key", () => {
    expect(mapItem({ data: { title: "x" } })).toBeNull();
    expect(mapItem(null)).toBeNull();
    expect(mapItem("string")).toBeNull();
  });

  it("degrades to empty strings for missing fields rather than throwing", () => {
    const item = mapItem({ key: "K", data: { key: "K", itemType: "book" } })!;
    expect(item.title).toBe("");
    expect(item.creators).toBe("");
    expect(item.year).toBe("");
    expect(item.doi).toBe("");
    expect(item.citeKey).toBe("");
  });

  it("picks publication title from whichever field the item type uses", () => {
    const book = mapItem({ key: "K", data: { key: "K", itemType: "book", publisher: "MIT Press" } })!;
    expect(book.publication).toBe("MIT Press");
  });
});

describe("LocalApiZoteroProvider — reads over the live API", () => {
  const fetcherReturning = (json: unknown, status = 200): ZoteroFetcher => vi.fn(async () => ({ status, json }));

  it("pings true on a 2xx", async () => {
    const p = new LocalApiZoteroProvider("http://localhost:23119/api/users/0", fetcherReturning([], 200));
    expect(await p.ping()).toBe(true);
  });

  it("pings false on a non-2xx or a thrown fetch", async () => {
    expect(await new LocalApiZoteroProvider("http://x", fetcherReturning([], 500)).ping()).toBe(false);
    const throwing: ZoteroFetcher = vi.fn(async () => {
      throw new Error("refused");
    });
    expect(await new LocalApiZoteroProvider("http://x", throwing).ping()).toBe(false);
  });

  it("lists items, mapping each", async () => {
    const p = new LocalApiZoteroProvider("http://x", fetcherReturning([ITEM_ENVELOPE]));
    const items = await p.listItems();
    expect(items).toHaveLength(1);
    expect(items[0]!.title).toBe("Attention Is All You Need");
  });

  it("scopes to a collection when asked (hits the collection path)", async () => {
    const fetcher = vi.fn(async (url: string) => ({ status: 200, json: /collections\/COLL01/.test(url) ? [ITEM_ENVELOPE] : [] }));
    const p = new LocalApiZoteroProvider("http://x", fetcher);
    const items = await p.listItems({ collectionKey: "COLL01" });
    expect(items).toHaveLength(1);
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("/collections/COLL01/items/top"));
  });

  it("returns [] on an API error instead of throwing", async () => {
    const p = new LocalApiZoteroProvider("http://x", fetcherReturning(null, 500));
    expect(await p.listItems()).toEqual([]);
    expect(await p.listCollections()).toEqual([]);
  });
});

describe("the write seam — read-only today, ready for tomorrow", () => {
  it("the read-only backend reports no write capability, with an honest reason", async () => {
    const backend = new ReadOnlyZoteroBackend();
    expect(backend.canWrite()).toBe(false);
    expect(backend.capabilityNote()).toMatch(/read-only/i);
    const edit: ZoteroFieldEdit = { itemKey: "K", libraryId: 0, baseVersion: 1, field: "Title", value: "x" };
    const res = await backend.applyEdit(edit);
    expect(res.supported).toBe(false);
  });

  it("with no write support, every Zotero column is read-only on the row", () => {
    const row = zoteroItemToRow(mapItem(ITEM_ENVELOPE)!, new ReadOnlyZoteroBackend());
    // The whole row is locked — the same mechanism that guards an Excel formula cell.
    expect(new Set(row.provenance.readOnlyFields)).toEqual(new Set(ZOTERO_COLUMNS));
  });

  it("the row carries the item key and version in provenance, so a future write has its address", () => {
    const row = zoteroItemToRow(mapItem(ITEM_ENVELOPE)!, new ReadOnlyZoteroBackend());
    expect(row.provenance.locator).toMatchObject({ itemKey: "ABCD1234", libraryId: 0, version: 42 });
    expect(row.provenance.extractor).toBe("zotero-library");
  });

  it("when a hypothetical write backend is enabled, editable metadata fields unlock — no other change", () => {
    // This is the whole point of the seam: swap the backend, and the SAME row mapper produces editable
    // rows. Simulate a future backend that permits writes.
    const writableBackend: ZoteroWriteBackend = {
      canWrite: () => true,
      capabilityNote: () => "ok",
      applyEdit: (): Promise<ZoteroWriteResult> => Promise.resolve({ supported: true, ok: true, newVersion: 2 }),
    };
    const row = zoteroItemToRow(mapItem(ITEM_ENVELOPE)!, writableBackend);
    const readOnly = new Set(row.provenance.readOnlyFields);
    // Editable metadata is now writable...
    expect(readOnly.has("Title")).toBe(false);
    expect(readOnly.has("DOI")).toBe(false);
    expect(readOnly.has("Tags")).toBe(false);
    // ...but identity/timestamp fields stay locked even with write support.
    expect(readOnly.has("Creators")).toBe(true);
    expect(readOnly.has("Cite Key")).toBe(true);
    expect(readOnly.has("Added")).toBe(true);
  });

  it("maps a whole library to rows", () => {
    const rows = zoteroItemsToRows([mapItem(ITEM_ENVELOPE)!, mapItem(ITEM_ENVELOPE)!], new ReadOnlyZoteroBackend());
    expect(rows).toHaveLength(2);
    expect(rows[0]!.cells["Title"]).toBe("Attention Is All You Need");
  });
});
