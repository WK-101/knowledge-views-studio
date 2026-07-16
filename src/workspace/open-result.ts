import { TFile, type App } from "obsidian";
import type { SearchResult } from "../services/index";

/**
 * Open a search result in the right way for its kind — the one place both the search view and the quick
 * launcher use, so their navigation can't drift. Notes/rows jump to the matched heading/line; PDFs open to
 * the page; links open their URL; Zotero hits open in Zotero. `newLeaf` opens in a new tab.
 */
export function openSearchResult(app: App, r: SearchResult, newLeaf = false): void {
  if (r.source === "zotero" || r.source === "zotero-annotation") {
    const key = r.source === "zotero" ? r.meta?.["zoteroKey"] : r.meta?.["parentKey"];
    if (typeof key === "string" && key !== "") window.open(`zotero://select/library/items/${key}`, "_blank");
    return;
  }
  if (r.source === "link") {
    const url = r.meta?.["url"];
    if (typeof url === "string" && /^https?:\/\//.test(url)) window.open(url, "_blank");
    return;
  }
  const path = r.meta?.["path"];
  if (typeof path !== "string") return;
  const section = String(r.meta?.["section"] ?? "");
  const heading = String(r.meta?.["heading"] ?? "");
  if (r.source === "pdf") {
    const page = /p\.(\d+)/.exec(section)?.[1];
    void app.workspace.openLinkText(page ? `${path}#page=${page}` : path, "", newLeaf);
    return;
  }
  if (r.source === "note" && heading !== "") {
    void app.workspace.openLinkText(`${path}#${heading}`, "", newLeaf);
    return;
  }
  if (r.source === "row" && typeof r.meta?.["line"] === "number") {
    const f = app.vault.getAbstractFileByPath(path);
    if (f instanceof TFile) void app.workspace.getLeaf(newLeaf).openFile(f, { eState: { line: r.meta["line"] } });
    return;
  }
  void app.workspace.openLinkText(path, "", newLeaf);
}
