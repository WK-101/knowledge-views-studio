import { normalizePath, TFile, type App } from "obsidian";
import type { ZoteroLibraryItem } from "../zotero/provider";
import type { KvsAnnotation } from "../../domain/index";
import { renderAnnotationsMarkdown, upsertAnnotationsRegion } from "../annotations/render";

/**
 * Literature notes — the bridge that turns a Zotero paper into a first-class Obsidian note you can think
 * with: link from concept notes, tag into your own system, place in the graph, and write your own synthesis
 * under. This is the workflow Zotero+Obsidian users actually live in, and the thing that was missing: our
 * Zotero features let you *view and search* papers, but a researcher's endpoint is *writing and linking*,
 * which only a real note in the vault allows.
 *
 * Three properties make this reduce friction instead of adding a chore:
 *
 *   1. **Idempotent by a durable key.** Every literature note records its Zotero item key in frontmatter
 *      (`zotero-key`). Creating a note for a paper first *looks up* that key across the vault — so a paper
 *      never gets two notes, a renamed title never forks it, and re-running "create" on a paper you've
 *      already noted just opens the existing note.
 *
 *   2. **A real note, not a data dump.** The metadata lands in frontmatter (authors, year, journal, doi,
 *      tags, the key), which makes the paper a proper Obsidian citizen — wikilinkable, taggable, and
 *      queryable by KVS's own note-properties source, so you can build dashboards over your literature.
 *      The abstract and annotations fill the body; a Notes section is left for you.
 *
 *   3. **Annotations refresh in place.** The annotations region is managed (via the same upsert used by the
 *      annotation-sync), so re-running on a paper whose Zotero annotations have grown updates just that
 *      region and never touches your own writing below it.
 */

export interface LiteratureNoteOptions {
  /** Folder for new literature notes (created if missing). Existing notes are found by key regardless. */
  readonly folder: string;
  /** Custom note template with {{placeholders}}; falls back to the built-in default when empty. */
  readonly template?: string;
  /** Rendered annotations to seed/refresh the note's Annotations region, if any were collected. */
  readonly annotations?: readonly KvsAnnotation[];
  /** Theme spec ("color=Theme; …") passed through to annotation rendering. */
  readonly themeSpec?: string;
}

/** Result of a find-or-create, so the caller can report "created" vs "opened" and focus the file. */
export interface LiteratureNoteResult {
  readonly file: TFile;
  readonly created: boolean;
}

/**
 * Index every literature note in the vault by its `zotero-key` frontmatter. Cheap (reads the metadata
 * cache, not file bodies), so a caller can both resolve find-or-create and show which papers already have
 * notes. A key maps to the first note that claims it; duplicates (shouldn't happen, but a user could make
 * one by hand) resolve to whichever the cache lists first.
 */
export function indexLiteratureNotes(app: App): Map<string, TFile> {
  const byKey = new Map<string, TFile>();
  for (const file of app.vault.getMarkdownFiles()) {
    const key: unknown = app.metadataCache.getFileCache(file)?.frontmatter?.["zotero-key"];
    if (typeof key === "string" && key !== "" && !byKey.has(key)) byKey.set(key, file);
  }
  return byKey;
}

/** Find an existing literature note for an item by its Zotero key, or null. */
export function findLiteratureNote(app: App, itemKey: string): TFile | null {
  return indexLiteratureNotes(app).get(itemKey) ?? null;
}

/** Strip characters Obsidian disallows in file names, and trim to something sane. */
function safeBaseName(item: ZoteroLibraryItem): string {
  // Prefer the cite key (stable, short, citation-friendly); fall back to the title, then the Zotero key.
  const raw = item.citeKey || item.title || item.key;
  const cleaned = raw.replace(/[\\/:*?"<>|#^[\]]/g, "").replace(/\s+/g, " ").trim();
  return (cleaned || item.key).slice(0, 120);
}

/** Placeholders a user can use in a custom literature-note template. */
export const LITERATURE_PLACEHOLDERS = [
  "title", "authors", "year", "journal", "doi", "url", "citeKey", "itemType", "key", "tags", "abstract", "zoteroLink",
] as const;

/** The values each placeholder resolves to for a given item. `annotations` is filled separately. */
function placeholderValues(item: ZoteroLibraryItem): Record<(typeof LITERATURE_PLACEHOLDERS)[number], string> {
  return {
    title: item.title,
    authors: item.creators,
    year: item.year,
    journal: item.publication,
    doi: item.doi,
    url: item.url,
    citeKey: item.citeKey,
    itemType: item.itemType,
    key: item.key,
    tags: item.tags.join(", "),
    abstract: item.extra["abstract"] ?? "",
    zoteroLink: `zotero://select/library/items/${item.key}`,
  };
}

/**
 * The built-in default note body. Kept as a real template string so the default and a user override travel
 * the same substitution path — the default is just the template we ship. IMPORTANT: whatever a user writes,
 * their template must still carry `zotero-key` in frontmatter, or find-or-create can't match the note; the
 * builder injects it defensively if it's missing (see {@link buildLiteratureNote}).
 */
const DEFAULT_TEMPLATE = `---
title: "{{title}}"
authors: "{{authors}}"
year: {{year}}
journal: "{{journal}}"
doi: "{{doi}}"
url: "{{url}}"
cite-key: "{{citeKey}}"
item-type: "{{itemType}}"
zotero-key: "{{key}}"
tags: [literature, {{tags}}]
---

# {{title}}

> [!info] Zotero
> {{authors}} ({{year}}) *{{journal}}*
> [Open in Zotero]({{zoteroLink}})

## Abstract

{{abstract}}

## Annotations

## Notes
`;

/** Substitute {{placeholder}} tokens in a template with an item's values. Unknown tokens are left as-is. */
function fillTemplate(template: string, item: ZoteroLibraryItem): string {
  const values = placeholderValues(item);
  return template.replace(/\{\{(\w+)\}\}/g, (match, name: string) => {
    return name in values ? values[name as keyof typeof values] : match;
  });
}

/**
 * Build the content of a literature note. With no custom template, the shipped default is used; a user's
 * template (from settings) travels the same path. Either way, the note is guaranteed to carry a
 * `zotero-key` in frontmatter — if a custom template omitted it, it's injected — because that key is what
 * makes find-or-create idempotent, and losing it would silently break duplicate protection.
 */
export function buildLiteratureNote(item: ZoteroLibraryItem, template?: string): string {
  const chosen = template && template.trim() !== "" ? template : DEFAULT_TEMPLATE;
  let out = fillTemplate(chosen, item);
  out = ensureZoteroKey(out, item.key);
  return out.endsWith("\n") ? out : out + "\n";
}

/**
 * Guarantee the rendered note has `zotero-key` in its frontmatter. If the template already emitted one
 * (the default does), this is a no-op. If a custom template dropped it, we insert it into the frontmatter
 * block (or create a minimal frontmatter block if there is none), so idempotent matching never breaks.
 */
function ensureZoteroKey(note: string, key: string): string {
  if (/^zotero-key:/m.test(note)) return note;
  const fmMatch = /^---\n([\s\S]*?)\n---/.exec(note);
  if (fmMatch) {
    return note.replace(/^---\n/, `---\nzotero-key: "${key}"\n`);
  }
  // No frontmatter at all — prepend a minimal block so the note is still matchable.
  return `---\nzotero-key: "${key}"\n---\n\n${note}`;
}

/**
 * Find-or-create the literature note for a Zotero item, seed/refresh its annotations, and return the file.
 *
 * If a note with this item's key already exists anywhere in the vault, it is reused (and its annotations
 * region refreshed when annotations were supplied) — never duplicated. Otherwise a new note is created in
 * the configured folder. The caller opens the returned file.
 */
export async function createOrOpenLiteratureNote(app: App, item: ZoteroLibraryItem, options: LiteratureNoteOptions): Promise<LiteratureNoteResult> {
  const existing = findLiteratureNote(app, item.key);
  if (existing) {
    if (options.annotations && options.annotations.length > 0) {
      await refreshAnnotations(app, existing, options.annotations, options.themeSpec);
    }
    return { file: existing, created: false };
  }

  // Ensure the target folder (and any missing parents) exists.
  const folder = normalizePath((options.folder || "Literature").replace(/\/+$/, ""));
  const parts = folder.split("/").filter((p) => p !== "");
  let acc = "";
  for (const part of parts) {
    acc = acc === "" ? part : `${acc}/${part}`;
    if (!app.vault.getAbstractFileByPath(acc)) await app.vault.createFolder(acc).catch(() => undefined);
  }

  // Pick a non-colliding path (a different paper could share a sanitized name).
  const base = safeBaseName(item);
  let path = `${folder}/${base}.md`;
  for (let i = 2; app.vault.getAbstractFileByPath(path); i++) path = `${folder}/${base} (${i}).md`;

  let content = buildLiteratureNote(item, options.template);
  if (options.annotations && options.annotations.length > 0) {
    const block = renderAnnotationsMarkdown(options.annotations, { themeMap: parseTheme(options.themeSpec) });
    content = upsertAnnotationsRegion(content, block);
  }
  const file = await app.vault.create(path, content);
  return { file, created: true };
}

async function refreshAnnotations(app: App, file: TFile, annotations: readonly KvsAnnotation[], themeSpec?: string): Promise<void> {
  const content = await app.vault.read(file);
  const block = renderAnnotationsMarkdown(annotations, { themeMap: parseTheme(themeSpec) });
  await app.vault.modify(file, upsertAnnotationsRegion(content, block));
}

/** Parse a "color=Theme; …" spec into the map renderAnnotationsMarkdown expects; empty when unset. */
function parseTheme(spec?: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const pair of (spec ?? "").split(";")) {
    const [k, v] = pair.split("=");
    if (k && v) out[k.trim().toLowerCase()] = v.trim();
  }
  return out;
}
