import type { Row, RowProvenance, SourceFileMeta } from "../../domain/model";
import type { ZoteroLibraryItem, ZoteroWriteBackend } from "./provider";

/**
 * Turns Zotero library items into the {@link Row} shape the rest of KVS already renders — table, cards,
 * board, calendar, gallery, pivot, filters, sorting, search. This is what makes a Zotero library *feel
 * native* rather than living in a bespoke panel: it is just another source of rows.
 *
 * The bidirectional seam lives in one place here: `provenance.readOnlyFields`. Today every field is
 * read-only, because Zotero cannot be written to locally — so editing any cell is blocked by the same
 * mechanism that already stops someone overwriting an Excel formula. When Zotero gains local writes and a
 * real write backend reports `canWrite()`, the *editable* fields simply drop out of this list, and the
 * existing edit path lights up with no other change. The write-block is not a special case bolted onto
 * Zotero; it is the same machinery, parameterised by what the backend currently permits.
 */

/** The columns we surface as first-class, in display order. Anything else is reachable via `extra`. */
export const ZOTERO_COLUMNS = [
  "Title",
  "Creators",
  "Year",
  "Type",
  "Publication",
  "Cite Key",
  "DOI",
  "Tags",
  "Collections",
  "Added",
  "Modified",
] as const;

/** Fields that could conceivably be edited in Zotero one day (metadata), vs. ones that never should. */
const POTENTIALLY_EDITABLE = new Set<string>(["Title", "Year", "Publication", "DOI", "Tags"]);
/** Fields that are Zotero-derived identity or timestamps — never user-editable even with write support. */
const ALWAYS_READ_ONLY = new Set<string>(["Creators", "Type", "Cite Key", "Collections", "Added", "Modified"]);

function itemToCells(item: ZoteroLibraryItem): Record<string, string> {
  return {
    Title: item.title,
    Creators: item.creators,
    Year: item.year,
    Type: item.itemType,
    Publication: item.publication,
    "Cite Key": item.citeKey,
    DOI: item.doi,
    Tags: item.tags.join(", "),
    Collections: item.collections.join(", "),
    Added: item.dateAdded,
    Modified: item.dateModified,
  };
}

/**
 * Which fields are read-only *right now*, given what the write backend permits. With no write support
 * (today), that's everything. With write support, it's the always-read-only identity/timestamp fields
 * plus anything not yet wired for editing — never the editable metadata fields.
 */
function readOnlyFieldsFor(writes: ZoteroWriteBackend): string[] {
  if (!writes.canWrite()) return [...ZOTERO_COLUMNS]; // read-only backend → the whole row is read-only
  return ZOTERO_COLUMNS.filter((c) => ALWAYS_READ_ONLY.has(c) || !POTENTIALLY_EDITABLE.has(c));
}

/**
 * A synthetic file meta for a Zotero item. Zotero items are not vault files, but the Row model expects a
 * `SourceFileMeta`; we give it a stable pseudo-path under a `zotero://` scheme so provenance is unique and
 * legible, and timestamps from the item so date-based views work.
 */
function syntheticFileMeta(item: ZoteroLibraryItem): SourceFileMeta {
  const path = `zotero://library/${item.libraryId}/items/${item.key}`;
  const added = Date.parse(item.dateAdded);
  const modified = Date.parse(item.dateModified);
  return {
    filePath: path,
    fileName: item.title || item.key,
    folderPath: `zotero://library/${item.libraryId}`,
    createdMs: Number.isFinite(added) ? added : 0,
    modifiedMs: Number.isFinite(modified) ? modified : 0,
    sizeBytes: 0,
  };
}

/** Map one item to a Row, tagging provenance with the Zotero key/version so a future write can find it. */
export function zoteroItemToRow(item: ZoteroLibraryItem, writes: ZoteroWriteBackend): Row {
  const cells = itemToCells(item);
  const provenance: RowProvenance = {
    filePath: `zotero://library/${item.libraryId}/items/${item.key}`,
    extractor: "zotero-library",
    // The locator carries exactly what a future write needs: which item, and the version it was read at
    // (for Zotero's If-Unmodified-Since-Version / 412 conflict protocol). Storing it now means the write
    // path has its address the day it becomes usable — no re-plumbing.
    locator: { itemKey: item.key, libraryId: item.libraryId, version: item.version },
    fingerprint: `${item.key}@${item.version}`,
    readOnlyFields: readOnlyFieldsFor(writes),
  };
  return { cells, file: syntheticFileMeta(item), provenance };
}

/** Map a whole library. The one call a Zotero-backed view makes to get its rows. */
export function zoteroItemsToRows(items: readonly ZoteroLibraryItem[], writes: ZoteroWriteBackend): Row[] {
  return items.map((it) => zoteroItemToRow(it, writes));
}
