import type { App, TFile } from "obsidian";
import { normalizeUrl } from "../../../shared/protocol";

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

/**
 * The frontmatter key used to link dedicated notes for a profile.
 *
 * Academic views match on the DOI, which is the right identity for a paper: the same paper lives at arXiv,
 * at the publisher, and as a PDF — three URLs, one DOI. Everything else matches on `source`, the page's
 * normalized URL, which is the only identity a general web capture reliably has. Before this, non-academic
 * views had **no** key at all, so promotion couldn't recognise an existing note and the row-note link only
 * worked for papers.
 */
export function dedicatedNoteKeyFor(profile: { academicKit?: boolean; dedicatedNoteKey?: string }): string {
  const explicit = (profile.dedicatedNoteKey ?? "").trim();
  if (explicit !== "") return explicit;
  return profile.academicKit ? "doi" : "source";
}

/**
 * Normalize a match value so equivalent forms compare equal.
 *
 * DOIs get their scheme/host prefix stripped. URL-ish keys go through the same normalisation the bridge
 * uses everywhere else, so a note whose `source` says `https://www.example.com/a/?utm_source=x` still
 * matches a row captured from `https://example.com/a` — the whole point of using the URL as identity.
 */
export function normalizeIdentifier(key: string, value: string): string {
  const v = (value ?? "").trim();
  if (v === "") return "";
  const k = key.toLowerCase();
  if (k === "doi") {
    return v
      .replace(/^https?:\/\/(dx\.)?doi\.org\//i, "")
      .replace(/^doi:\s*/i, "")
      .trim()
      .toLowerCase();
  }
  if (k === "source" || k === "url" || k === "link") {
    return normalizeUrl(v).toLowerCase();
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
  return findInIndex(getDedicatedNoteIndex(app, key), key, value);
}

/**
 * A process-wide, incrementally-maintained index of notes by a frontmatter field, so a view's note-indicator
 * and "promote" never rescan the whole vault on the render path. It's built once (lazily, on first read),
 * then kept current by applying single-file updates from the plugin's metadata-cache events — so editing a
 * cell, which changes one file's metadata, costs one map update instead of an O(all-notes) rescan. This was
 * the cause of the dashboard feeling slow after editing: every edit invalidated the index and the next render
 * rebuilt it from scratch.
 *
 * We keep a reverse `byPath` map (path → the value that file contributed) so an update can remove a file's
 * old entry precisely. Duplicate values (two notes with the same DOI) resolve first-writer-wins; that's fine
 * for an indicator, and DOIs are effectively unique in practice.
 */
interface FrontmatterIndex {
  key: string;
  byValue: Map<string, TFile>;
  byPath: Map<string, string>;
}
let fmIndex: FrontmatterIndex | null = null;

/** Read and normalize a file's value for the index key, or "" if absent. */
function readIndexValue(app: App, file: TFile, key: string): string {
  const raw: unknown = app.metadataCache.getFileCache(file)?.frontmatter?.[key];
  if (typeof raw !== "string" && typeof raw !== "number") return "";
  return normalizeIdentifier(key, String(raw));
}

/** The frontmatter index for a field. Built once per key; thereafter served from memory and kept current by
 *  the incremental update hooks below (so reads never trigger a vault scan). */
export function getDedicatedNoteIndex(app: App, key: string): Map<string, TFile> {
  if (fmIndex && fmIndex.key === key) return fmIndex.byValue;
  const byValue = new Map<string, TFile>();
  const byPath = new Map<string, string>();
  if (key.trim() !== "") {
    for (const file of app.vault.getMarkdownFiles()) {
      const norm = readIndexValue(app, file, key);
      if (norm === "") continue;
      byPath.set(file.path, norm);
      if (!byValue.has(norm)) byValue.set(norm, file);
    }
  }
  fmIndex = { key, byValue, byPath };
  return byValue;
}

/** Apply a single file's metadata change to the live index (from a metadata-cache "changed" event). Cheap:
 *  one read plus at most two map writes. No-op until the index has been built. */
export function updateDedicatedNoteIndex(app: App, file: TFile): void {
  if (!fmIndex) return;
  const { key, byValue, byPath } = fmIndex;
  const oldNorm = byPath.get(file.path);
  const newNorm = readIndexValue(app, file, key);
  if (oldNorm === (newNorm === "" ? undefined : newNorm)) return; // unchanged
  if (oldNorm !== undefined) {
    byPath.delete(file.path);
    if (byValue.get(oldNorm)?.path === file.path) byValue.delete(oldNorm);
  }
  if (newNorm !== "") {
    byPath.set(file.path, newNorm);
    if (!byValue.has(newNorm)) byValue.set(newNorm, file);
  }
}

/** Remove a file from the live index (from a metadata-cache "deleted" event or a rename's old path). */
export function removeFromDedicatedNoteIndex(path: string): void {
  if (!fmIndex) return;
  const oldNorm = fmIndex.byPath.get(path);
  if (oldNorm === undefined) return;
  fmIndex.byPath.delete(path);
  if (fmIndex.byValue.get(oldNorm)?.path === path) fmIndex.byValue.delete(oldNorm);
}

/** Drop the whole index so the next read rebuilds (used only when the match key itself might change). */
export function invalidateDedicatedNoteIndex(): void {
  fmIndex = null;
}
