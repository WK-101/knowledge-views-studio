import type { IndexDoc } from "../search/search-index";
import type { ZoteroAnnotationRecord, ZoteroLibraryItem } from "./provider";

/**
 * Turns a Zotero library — its items and their annotations — into the same {@link IndexDoc} shape the vault
 * search already indexes. This is what lets one search box find a paper in your Zotero library, or a
 * highlight you made in it, alongside your notes: the Zotero documents live in the *same* index, ranked by
 * the *same* relevance model, filtered by the *same* scope machinery. No parallel search path.
 *
 * Two document kinds, both tagged with a distinct `source` so a user can scope to them and so a hit is
 * labelled clearly:
 *
 *   - `source: "zotero"` — one document per library item: title, creators, abstract, tags, publication.
 *     Title and tags go in boosted fields (a title match should outrank an abstract match), exactly as a
 *     note's title does.
 *   - `source: "zotero-annotation"` — one document per annotation: the quoted text plus the reader's
 *     comment. This is the payoff of integrating annotations — the words you highlighted become findable.
 *
 * IDs are namespaced under a `zotero://` pseudo-path so they never collide with vault-file doc ids and so
 * the reconcile/drop logic can clear all Zotero docs by that prefix on a refresh.
 */

/** The id prefix all Zotero-sourced documents share, for bulk removal on refresh. */
export const ZOTERO_DOC_PREFIX = "zotero://";

/** One search document per library item — the metadata a person would search a paper by. */
export function itemToSearchDoc(item: ZoteroLibraryItem): IndexDoc {
  // Body = the free-text fields worth matching on; boosted fields = title and tags.
  const abstract = item.extra["abstract"] ?? "";
  const bodyParts = [item.creators, item.publication, item.year, abstract, item.doi, item.citeKey].filter((s) => s !== "");
  const fields: Record<string, string> = { title: item.title };
  if (item.tags.length > 0) fields["tag"] = item.tags.join(" ");
  return {
    id: `${ZOTERO_DOC_PREFIX}item/${item.libraryId}/${item.key}`,
    text: bodyParts.join("\n"),
    fields,
    source: "zotero",
    location: item.itemType,
    meta: {
      // A search hit can point back at the item; the modified time feeds the recency signal like a file's.
      zoteroKey: item.key,
      title: item.title,
      ...(Number.isFinite(Date.parse(item.dateModified)) ? { mtime: Date.parse(item.dateModified) } : {}),
    },
  };
}

/** One search document per annotation — the words highlighted, plus the note on them. */
export function annotationToSearchDoc(a: ZoteroAnnotationRecord): IndexDoc {
  const text = [a.text, a.comment].filter((s) => s !== "").join("\n");
  return {
    id: `${ZOTERO_DOC_PREFIX}annotation/${a.key}`,
    text,
    source: "zotero-annotation",
    location: a.pageLabel ? `p. ${a.pageLabel}` : a.type,
    meta: {
      zoteroKey: a.key,
      parentKey: a.parentKey,
      // The comment is the higher-signal part; surface a snippet for display.
      snippet: (a.comment || a.text).slice(0, 120),
    },
  };
}

/** Build all Zotero search documents from a library snapshot. The one call the indexer makes. */
export function zoteroSearchDocs(items: readonly ZoteroLibraryItem[], annotations: readonly ZoteroAnnotationRecord[]): IndexDoc[] {
  const docs: IndexDoc[] = [];
  for (const item of items) docs.push(itemToSearchDoc(item));
  for (const a of annotations) docs.push(annotationToSearchDoc(a));
  return docs;
}
