/**
 * The Zotero data provider: a transport-agnostic view of a Zotero library, designed from the outset so
 * that today's read-only reality and tomorrow's bidirectional editing are the *same shape*, not a
 * retrofit.
 *
 * The situation this is built for: Zotero's local API (localhost:23119) is currently **read-only** — the
 * Zotero developers have said write support is planned but not built, and every local endpoint is GET.
 * So a genuinely native, local, editable link to Zotero cannot exist yet. But that will change, and when
 * it does we do not want to discover that our read path and a bolted-on write path are two different
 * worlds glued together.
 *
 * So the design here separates three concerns deliberately:
 *
 *   1. **What a Zotero library looks like** — {@link ZoteroLibraryItem}, a plain data shape, independent
 *      of how it was fetched.
 *   2. **Reading it** — {@link ZoteroProvider.listItems} and friends, implemented now against the live
 *      local API.
 *   3. **Writing it** — {@link ZoteroWriteBackend}, an interface that *exists now* but whose only current
 *      implementation reports `supported: false`. Every write flows through it. The day Zotero ships local
 *      writes (or a user opts into the cloud Web API), we add one backend that returns `supported: true`
 *      and actually performs the write — and nothing else in the codebase changes, because everything
 *      already asks the backend "can you?" before offering an edit and routes the edit through it.
 *
 * That is what keeps the eventual bidirectional support from feeling like patchwork: the seam for it is
 * load-bearing from day one, exercised by the read-only backend, rather than a hole we punch later.
 */

/** A fetcher over Zotero's HTTP API — reused from the annotation transport, injected for testing. */
export type ZoteroFetcher = (url: string) => Promise<{ status: number; json?: unknown; text?: string }>;

/** One item from a Zotero library, flattened to the fields a library view needs. */
export interface ZoteroLibraryItem {
  /** Zotero object key — stable identity, and the address a future write would target. */
  readonly key: string;
  /** Library id the item lives in (user library is 0 in the local API's default path). */
  readonly libraryId: number;
  /** Item version — Zotero's optimistic-concurrency token; a write must echo it (the 412 protocol). */
  readonly version: number;
  readonly itemType: string;
  readonly title: string;
  /** Formatted creator summary, e.g. "Smith, Jones, and Lee". */
  readonly creators: string;
  readonly year: string;
  readonly publication: string;
  readonly doi: string;
  readonly url: string;
  readonly tags: readonly string[];
  readonly collections: readonly string[];
  readonly dateAdded: string;
  readonly dateModified: string;
  /** The Better BibTeX / Zotero cite key, when present in the item's `extra` or via a BBT field. */
  readonly citeKey: string;
  /** Attachment keys hanging off this item (PDFs etc.), for opening in a reader. */
  readonly attachmentKeys: readonly string[];
  /** Everything else, unflattened, so a column can bind to a field we did not promote. */
  readonly extra: Readonly<Record<string, string>>;
}

/** A Zotero collection (folder) — for the tree/scope, mirroring what zotero-lib-view shows from a file. */
export interface ZoteroCollection {
  readonly key: string;
  readonly name: string;
  readonly parentKey: string | null;
  readonly itemCount: number;
}

/**
 * A Zotero annotation, flattened for search indexing. Deliberately light: search needs the words (the
 * quoted text and the user's comment) and enough to point back at the source, not the full geometry.
 */
export interface ZoteroAnnotationRecord {
  readonly key: string;
  /** The attachment/item this annotation hangs off, so a search hit can name its source. */
  readonly parentKey: string;
  readonly type: string; // highlight | note | underline | image | ink
  readonly text: string; // the quoted passage
  readonly comment: string; // the reader's note
  readonly pageLabel: string;
}

/** Options for listing items — scope to a collection, cap the count, etc. */
export interface ZoteroListOptions {
  readonly collectionKey?: string;
  readonly limit?: number;
  /** A full-text-ish query passed to Zotero's `q` parameter (searches title/creator/etc.). */
  readonly query?: string;
}

// ---------------------------------------------------------------------------
// The write seam — defined now, real later
// ---------------------------------------------------------------------------

/** A single field edit to push back to Zotero, once that is possible. */
export interface ZoteroFieldEdit {
  readonly itemKey: string;
  readonly libraryId: number;
  /** The item version the edit was made against, for the If-Unmodified-Since-Version / 412 protocol. */
  readonly baseVersion: number;
  readonly field: string;
  readonly value: string;
}

/** The outcome of attempting a write. `supported: false` is the whole story today. */
export type ZoteroWriteResult =
  | { readonly supported: false; readonly reason: string }
  | { readonly supported: true; readonly ok: true; readonly newVersion: number }
  | { readonly supported: true; readonly ok: false; readonly conflict: boolean; readonly reason: string };

/**
 * How writes reach Zotero. The one interface the whole app consults before offering an edit or trying to
 * save one. Today only {@link ReadOnlyZoteroBackend} implements it, always reporting unsupported; a local
 * or cloud write backend added later implements the same three methods and the rest of the app is none
 * the wiser.
 */
export interface ZoteroWriteBackend {
  /** Whether writing is possible at all right now. Drives whether the UI even offers an edit affordance. */
  canWrite(): boolean;
  /** A human-facing explanation of the current write capability (or lack of it), for settings/tooltips. */
  capabilityNote(): string;
  /** Attempt to apply one field edit. Never throws; returns a result the caller reports. */
  applyEdit(edit: ZoteroFieldEdit): Promise<ZoteroWriteResult>;
}

/**
 * The current, honest write backend: none. It reports unsupported for every edit, with a reason that
 * points at the actual cause (Zotero's local API is read-only) rather than implying our own limitation.
 * Because it satisfies the full {@link ZoteroWriteBackend} interface, the edit-routing code paths are
 * real and exercised today — so when a working backend replaces this one, those paths already work.
 */
export class ReadOnlyZoteroBackend implements ZoteroWriteBackend {
  canWrite(): boolean {
    return false;
  }

  capabilityNote(): string {
    return "Zotero's local API is currently read-only, so edits made here can't yet be saved back to Zotero. When Zotero adds local write support, this will start working with no change needed on your part.";
  }

  applyEdit(_edit: ZoteroFieldEdit): Promise<ZoteroWriteResult> {
    return Promise.resolve({
      supported: false,
      reason: "Writing to Zotero's local library is not yet supported by Zotero itself.",
    });
  }
}

/**
 * The read+write face of a Zotero library. Reads are live now; the write backend is the seam above.
 * A view holds one of these and never cares which transport is underneath.
 */
export interface ZoteroProvider {
  /** Is the provider reachable (Zotero running, local API enabled)? Cheap, for status display. */
  ping(): Promise<boolean>;
  listCollections(): Promise<ZoteroCollection[]>;
  listItems(options?: ZoteroListOptions): Promise<ZoteroLibraryItem[]>;
  getItem(key: string): Promise<ZoteroLibraryItem | null>;
  /** Every annotation in the library, in one shot — for bulk search indexing. */
  listAllAnnotations(): Promise<ZoteroAnnotationRecord[]>;
  /** The write backend — consult `.canWrite()` before offering edits; route edits through `.applyEdit()`. */
  readonly writes: ZoteroWriteBackend;
}
