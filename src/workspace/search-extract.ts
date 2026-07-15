import { TFile, type App } from "obsidian";
import { extractEpubText, extractOfficeText, extractPdfText, noteToDocs, rowsToDocs, sectionsToDocs, type IndexDoc } from "../services/index";
import type { RelevanceWeights } from "../services/search/relevance";
import type { DeviceProfile } from "../util/device";

/** Every file extension the search indexer knows how to read. */
export const INDEXABLE_EXTENSIONS = new Set(["md", "pdf", "docx", "xlsx", "pptx", "epub"]);

export interface IndexScope {
  /** The tunable relevance weights -- what counts, and how much. */
  readonly relevance?: RelevanceWeights;
  /** Which engine produces semantic vectors: the built-in one, or the optional neural model. */
  readonly semanticEngine?: "builtin" | "neural";
  /** Read the full text of attachments (PDF/Word/PowerPoint/EPUB), not just notes. */
  readonly attachments: boolean;
  /** Do that on a phone or tablet too — off by default, and deliberately a separate answer. */
  readonly attachmentsOnMobile?: boolean;
  /** Whether Excel is enabled as a data source. When it isn't, .xlsx files are ignored entirely —
   *  including by search, which is what that setting promises. */
  readonly excel: boolean;
}

/**
 * What a phone is actually allowed to do.
 *
 * Settings live in `data.json`, and `data.json` syncs. So a choice made on a laptop — "yes, index the
 * full text of every PDF"; "yes, use the neural engine" — arrives on the phone as a decision the phone
 * never made and cannot afford: pdf.js over a library of books, or downloading and running a
 * sentence-transformer, on a battery, in a webview with a fraction of the memory.
 *
 * The device therefore gets a veto, applied here, in one pure place, rather than trusted to a scattering
 * of `if (isMobile)` at the call sites that someone will one day forget to add:
 *
 *   - **Attachments** are indexed on mobile only if the user said so *for mobile*, as a separate answer.
 *     Notes are always indexed: they are cheap, and they are the reason search exists.
 *   - **The neural engine** never runs on mobile. The built-in engine downloads nothing and costs little,
 *     so semantic search still works — it falls back to the engine a phone can carry.
 *
 * Neither degrades silently: the settings panel says both in words, so a phone behaving differently is a
 * documented difference rather than a mysterious one.
 */
export function applyDevicePolicy(scope: IndexScope, device: DeviceProfile): IndexScope {
  if (!device.mobile) return scope;
  return {
    ...scope,
    attachments: scope.attachments && scope.attachmentsOnMobile === true,
    semanticEngine: "builtin",
  };
}

/**
 * Which extensions to index, given the user's choices. Notes are always in scope (they cost almost
 * nothing to read); attachments only when asked for, and Excel only when Excel is enabled at all.
 * Pure, so the rule can be tested rather than trusted.
 */
export function indexableExtensions(scope: IndexScope): Set<string> {
  const exts = new Set<string>(["md"]);
  if (!scope.attachments) return exts;
  for (const ext of ["pdf", "docx", "pptx", "epub"]) exts.add(ext);
  if (scope.excel) exts.add("xlsx");
  return exts;
}

/** Every vault file the indexer should cover, given the user's choices. */
export function indexableFiles(app: App, scope: IndexScope): TFile[] {
  const exts = indexableExtensions(scope);
  return app.vault.getFiles().filter((f) => exts.has(f.extension.toLowerCase()));
}

/** Read one file and produce its search documents (notes + rows, or per-page/slide/chapter attachment text). */
export async function fileToSearchDocs(app: App, file: TFile): Promise<IndexDoc[]> {
  const ext = file.extension.toLowerCase();
  const docs = await extractDocs(app, file, ext);
  const mtime = file.stat.mtime;
  return docs.map((d) => ({ ...d, meta: { ...(d.meta ?? {}), mtime } }));
}

async function extractDocs(app: App, file: TFile, ext: string): Promise<IndexDoc[]> {
  if (ext === "md") {
    const content = await app.vault.read(file);
    const docs = [...noteToDocs(file.path, content), ...rowsToDocs(file.path, content)];
    const tags = collectTags(app, file);
    if (tags === "") return docs;
    // Add accurate tags (from Obsidian's cache) as a `tag` field on every note section.
    return docs.map((d) => (d.source === "note" ? { ...d, fields: { ...(d.fields ?? {}), tag: tags } } : d));
  }
  if (!INDEXABLE_EXTENSIONS.has(ext)) return [];
  const bytes = await app.vault.readBinary(file);
  switch (ext) {
    case "pdf":
      return sectionsToDocs("pdf", file.path, "pdf", "pdf", await extractPdfText(bytes));
    case "docx":
      return sectionsToDocs("docx", file.path, "docx", "docx", extractOfficeText(bytes, "word"));
    case "xlsx":
      return sectionsToDocs("xlsx", file.path, "xlsx", "xlsx", extractOfficeText(bytes, "excel"));
    case "pptx":
      return sectionsToDocs("pptx", file.path, "pptx", "pptx", extractOfficeText(bytes, "powerpoint"));
    case "epub":
      return sectionsToDocs("epub", file.path, "epub", "epub", extractEpubText(bytes));
    default:
      return [];
  }
}

/** All of a note's tags (inline + frontmatter) from Obsidian's metadata cache, #-stripped + lowercased. */
function collectTags(app: App, file: TFile): string {
  const cache = app.metadataCache.getFileCache(file);
  const set = new Set<string>();
  for (const t of cache?.tags ?? []) set.add(t.tag.replace(/^#/, "").toLowerCase());
  const fm: unknown = cache?.frontmatter?.["tags"] ?? cache?.frontmatter?.["tag"]; // frontmatter is `any`
  if (Array.isArray(fm)) for (const t of fm) set.add(String(t).replace(/^#/, "").toLowerCase());
  else if (typeof fm === "string") for (const t of fm.split(/[,\s]+/)) if (t) set.add(t.replace(/^#/, "").toLowerCase());
  return [...set].join(" ");
}
