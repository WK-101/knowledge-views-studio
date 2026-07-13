import { Notice, TFile, type App } from "obsidian";
import {
  allPaperAttachments,
  fetchZoteroAnnotations,
  findZoteroKeysByDoi,
  mergeAnnotations,
  parseThemeMap,
  pdfAnnotationStore,
  readOfficeAnnotations,
  renderAnnotationsMarkdown,
  upsertAnnotationsRegion,
  zoteroDeepLink,
  zoteroKeysFromAttachments,
} from "../services/index";
import type { Attachment } from "../services/index";
import type { KvsAnnotation } from "../domain/index";
import { createZoteroFetcher } from "./zotero-transport";

export interface ZoteroSyncConfig {
  readonly enabled: boolean;
  readonly base: string;
}

export interface AnnotationSyncOptions {
  readonly zotero?: ZoteroSyncConfig;
  /** Colour → theme spec ("color=Theme; …") for the callout labels. */
  readonly themeSpec?: string;
}

/** Read embedded annotations from every PDF attached to `file` (and, if enabled, from Zotero) and
 *  write the unified section. */
export async function syncPaperAnnotations(app: App, file: TFile, options: AnnotationSyncOptions = {}): Promise<void> {
  const zotero = options.zotero;
  const content = await app.vault.read(file);
  const attachments = allPaperAttachments(content);
  const zoteroEnabled = Boolean(zotero?.enabled);
  const notice = new Notice("Reading annotations…", 0);
  const checked: string[] = [];
  const skipped: string[] = [];
  try {
    const groups: KvsAnnotation[][] = [];
    let missing = 0;
    for (const att of attachments) {
      if (!att.isLink) {
        const reason = skipReason(att);
        if (reason) skipped.push(`${att.target} — ${reason}`);
        continue;
      }
      const target = app.metadataCache.getFirstLinkpathDest(att.target, file.path);
      if (att.kind === "pdf" || att.kind === "word" || att.kind === "excel" || att.kind === "powerpoint") {
        if (!(target instanceof TFile)) {
          missing++;
          continue;
        }
        const bytes = await app.vault.readBinary(target);
        const anns = att.kind === "pdf" ? await pdfAnnotationStore.read(bytes, att.target) : readOfficeAnnotations(bytes, att.target, att.kind);
        groups.push(anns);
        checked.push(`${att.target} (${anns.length})`);
      } else {
        const reason = skipReason(att);
        if (reason) skipped.push(`${att.target} — ${reason}`);
      }
    }
    if (zoteroEnabled && zotero) {
      const fetcher = createZoteroFetcher();
      const debug: (m: string) => void = (m) => console.debug("[KVS Zotero]", m);
      const doi = readDoi(app, file, content);
      const linkKeys = zoteroKeysFromAttachments(attachments);
      debug(`DOI from note: ${doi || "(none)"} | zotero:// link keys: ${linkKeys.join(", ") || "none"}`);
      const keys = new Set(linkKeys);
      if (doi !== "") {
        notice.setMessage("Finding this paper in Zotero…");
        const doiKeys = await findZoteroKeysByDoi(zotero.base, doi, fetcher, debug);
        for (const k of doiKeys) keys.add(k);
      }
      let zAnns: KvsAnnotation[] = [];
      if (keys.size > 0) {
        notice.setMessage("Reading Zotero annotations…");
        zAnns = await fetchZoteroAnnotations(zotero.base, [...keys], fetcher, debug);
        groups.push(zAnns);
        checked.push(`Zotero (${zAnns.length})`);
      }
      if (zAnns.length === 0 && keys.size > 0) skipped.push(`Zotero — found ${keys.size} item(s) but no annotations (see console, Ctrl+Shift+I)`);
    }
    const merged = mergeAnnotations(...groups);
    const block = renderAnnotationsMarkdown(merged, { linkFor: linkForAnnotation, themeMap: parseThemeMap(options.themeSpec ?? "") });
    await app.vault.modify(file, upsertAnnotationsRegion(content, block));
    notice.hide();
    reportCoverage(merged.length, missing, checked, skipped);
  } catch (error) {
    notice.hide();
    console.error("[KVS] annotation sync failed:", error);
    new Notice(`Couldn't read annotations: ${error instanceof Error ? error.message : "unexpected error"}`);
  }
}

/** Why an attachment couldn't be checked for annotations, or null if it can be. */
function skipReason(a: Attachment): string | null {
  if (!a.isLink) return /^zotero:/i.test(a.target) ? null : "web page — annotate in Zotero; it arrives via the Zotero link / DOI";
  switch (a.kind) {
    case "pdf":
    case "word":
    case "excel":
    case "powerpoint":
      return null;
    case "image":
      return "images carry no annotations";
    case "epub":
      return "EPUB annotations aren't embedded — they come from Zotero (link its item / DOI)";
    default:
      return "no annotation format is available for this file type";
  }
}

/** Notice summarising coverage: total synced, and what was skipped + why. */
function reportCoverage(total: number, missing: number, checked: string[], skipped: string[]): void {
  const parts: string[] = [total > 0 ? `Synced ${total} annotation(s).` : "No annotations found — the Annotations section is now empty."];
  if (missing > 0) parts.push(`${missing} attached file(s) not found in the vault.`);
  if (skipped.length > 0) parts.push(`Not checked:\n• ${skipped.join("\n• ")}`);
  new Notice(parts.join("\n"), skipped.length > 0 ? 12000 : 6000);
  console.debug("[KVS] coverage — checked:", checked.join(", ") || "none", "| skipped:", skipped.join(" | ") || "none");
}

/** DOI from the note's frontmatter (cache first, then parse the content in case the cache is stale). */
function readDoi(app: App, file: TFile, content: string): string {
  const fm = app.metadataCache.getFileCache(file)?.frontmatter;
  const cached: unknown = fm?.doi ?? fm?.DOI; // Obsidian types frontmatter as `any`
  if (typeof cached === "string" && cached.trim() !== "") return cached.trim();
  const m = /^doi:\s*["']?([^"'\n]+)["']?\s*$/im.exec(content.split(/^---\s*$/m)[1] ?? "");
  return m ? (m[1] ?? "").trim() : "";
}


/** Deep link for an annotation: a Zotero reader link for Zotero-sourced ones, else the PDF page. */
function linkForAnnotation(a: KvsAnnotation): string {
  if (a.source === "zotero") {
    const key = a.attachment.startsWith("zotero:") ? a.attachment.slice("zotero:".length) : a.attachment;
    return zoteroDeepLink(key, a.page);
  }
  return `${a.attachment}#page=${a.page}`;
}
