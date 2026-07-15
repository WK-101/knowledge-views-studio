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
  const name = item.title || item.key;
  // The header line mirrors how a file hit reads ("<name> · <detail>"): the paper's name first, then a
  // human label for the item type, so the result is legible instead of showing a bare "journalArticle".
  const typeLabel = humanItemType(item.itemType);
  return {
    id: `${ZOTERO_DOC_PREFIX}item/${item.libraryId}/${item.key}`,
    text: bodyParts.join("\n"),
    fields,
    source: "zotero",
    location: typeLabel ? `${name} · ${typeLabel}` : name,
    meta: {
      // Carried like a file doc's meta so the header, the answer view, and jump-to all have the name/key.
      zoteroKey: item.key,
      title: name,
      ...(item.creators ? { subtitle: item.creators } : {}),
      ...(Number.isFinite(Date.parse(item.dateModified)) ? { mtime: Date.parse(item.dateModified) } : {}),
    },
  };
}

/** Turn Zotero's camelCase item types into readable labels ("journalArticle" → "Journal article"). */
function humanItemType(t: string): string {
  if (!t) return "";
  const spaced = t.replace(/([a-z])([A-Z])/g, "$1 $2");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}

/** One search document per annotation — the words highlighted, plus the note on them. */
export function annotationToSearchDoc(a: ZoteroAnnotationRecord, parentName?: string): IndexDoc {
  const text = [a.text, a.comment].filter((s) => s !== "").join("\n");
  const page = a.pageLabel ? `p. ${a.pageLabel}` : humanAnnotationType(a.type);
  // Name the source paper when we can resolve it, so the header reads like a file hit ("<paper> · p. 12")
  // instead of a bare page number with no context.
  const name = parentName && parentName !== "" ? `${parentName} · ${page}` : `Annotation · ${page}`;
  return {
    id: `${ZOTERO_DOC_PREFIX}annotation/${a.key}`,
    text,
    source: "zotero-annotation",
    location: name,
    meta: {
      zoteroKey: a.key,
      parentKey: a.parentKey,
      ...(parentName ? { title: parentName } : {}),
      page: a.pageLabel || "",
      // The comment is the higher-signal part; surface a snippet for display fallback.
      snippet: (a.comment || a.text).slice(0, 120),
    },
  };
}

/** Readable label for an annotation kind, used when there's no page number. */
function humanAnnotationType(t: string): string {
  const map: Record<string, string> = { highlight: "Highlight", underline: "Underline", note: "Note", image: "Image", ink: "Ink" };
  return map[t.toLowerCase()] ?? "Annotation";
}

/** Build all Zotero search documents from a library snapshot. The one call the indexer makes. */
export function zoteroSearchDocs(items: readonly ZoteroLibraryItem[], annotations: readonly ZoteroAnnotationRecord[]): IndexDoc[] {
  // Index item keys → title so an annotation can name the paper it belongs to. A Zotero annotation's
  // parentItem is usually its attachment, whose own parent is the paper; we can only resolve the title
  // when the parent key maps directly to a top-level item we fetched, so this is best-effort by design and
  // falls back to a generic label otherwise (never a wrong title).
  const titleByKey = new Map<string, string>();
  for (const item of items) titleByKey.set(item.key, item.title || item.key);

  const docs: IndexDoc[] = [];
  for (const item of items) docs.push(itemToSearchDoc(item));
  for (const a of annotations) docs.push(annotationToSearchDoc(a, titleByKey.get(a.parentKey)));
  return docs;
}
