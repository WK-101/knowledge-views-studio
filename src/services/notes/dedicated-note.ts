import type { App, TFile } from "obsidian";

/**
 * A row's "dedicated note" is the note that stands for that item — a paper's literature note, say. The old
 * way of finding it was fragile: a `[[wikilink]]` stored in the row, or a note that happened to be *named*
 * after the cite key. Both break when a note is renamed or moved, and both create duplicates when the link
 * is missing, so promoting the same paper twice made two notes.
 *
 * The robust way is to match on a stable identifier written into the note's frontmatter — for academic work,
 * the DOI. A note is the dedicated note for a row iff `frontmatter[key]` equals the row's value for that key
 * (its DOI), no matter where the note lives or what it's called. This module builds that index from the
 * metadata cache and normalizes values so trivial differences (a `https://doi.org/` prefix, case) still
 * match.
 */

/** The frontmatter key used to link dedicated notes for a profile. Defaults to "doi" for academic views. */
export function dedicatedNoteKeyFor(profile: { academicKit?: boolean; dedicatedNoteKey?: string }): string {
  const explicit = (profile.dedicatedNoteKey ?? "").trim();
  if (explicit !== "") return explicit;
  return profile.academicKit ? "doi" : "";
}

/** Normalize a match value so equivalent forms compare equal. DOIs get their scheme/host prefix stripped. */
export function normalizeIdentifier(key: string, value: string): string {
  const v = (value ?? "").trim();
  if (v === "") return "";
  if (key.toLowerCase() === "doi") {
    return v
      .replace(/^https?:\/\/(dx\.)?doi\.org\//i, "")
      .replace(/^doi:\s*/i, "")
      .trim()
      .toLowerCase();
  }
  return v.toLowerCase();
}

/**
 * Build an index of vault notes keyed by a frontmatter field's (normalized) value. First writer wins if two
 * notes share the same identifier, so a stray duplicate never hides the original. Reads only the metadata
 * cache — no file I/O — so it's cheap enough to build once per render.
 */
export function indexNotesByFrontmatter(app: App, key: string): Map<string, TFile> {
  const byValue = new Map<string, TFile>();
  if (key.trim() === "") return byValue;
  for (const file of app.vault.getMarkdownFiles()) {
    const raw: unknown = app.metadataCache.getFileCache(file)?.frontmatter?.[key];
    if (typeof raw !== "string" && typeof raw !== "number") continue;
    const norm = normalizeIdentifier(key, String(raw));
    if (norm !== "" && !byValue.has(norm)) byValue.set(norm, file);
  }
  return byValue;
}

/** Find the dedicated note for an identifier value using a prebuilt index, or null. */
export function findInIndex(index: Map<string, TFile>, key: string, value: string): TFile | null {
  const norm = normalizeIdentifier(key, value);
  return norm === "" ? null : index.get(norm) ?? null;
}

/** One-shot lookup (builds the index, then queries) for callers that don't need to reuse it. */
export function findDedicatedNote(app: App, key: string, value: string): TFile | null {
  if (key.trim() === "" || (value ?? "").trim() === "") return null;
  return findInIndex(indexNotesByFrontmatter(app, key), key, value);
}
