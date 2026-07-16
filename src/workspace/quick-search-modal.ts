import { SuggestModal, type App } from "obsidian";
import { orderByTitleFirst, type SearchResult } from "../services/index";
import type { SearchIndexer } from "./search-indexer";
import { openSearchResult } from "./open-result";

/**
 * A Spotlight-style quick launcher over the KVS search index: type a few letters, the note you meant is
 * pinned to the top (via {@link orderByTitleFirst}), press Enter to jump. It reuses the already-built index —
 * no second index, no extra memory — and collapses a note's many section documents down to one row so the
 * list reads like a note picker, not a pile of fragments. Cmd/Ctrl+Enter opens in a new tab.
 */
export class QuickSearchModal extends SuggestModal<SearchResult> {
  constructor(
    app: App,
    private readonly indexer: SearchIndexer,
  ) {
    super(app);
    this.setPlaceholder("Jump to a note, file, or link…");
    this.setInstructions([
      { command: "↵", purpose: "open" },
      { command: "⌘/Ctrl ↵", purpose: "open in new tab" },
      { command: "esc", purpose: "dismiss" },
    ]);
  }

  getSuggestions(query: string): SearchResult[] {
    const q = query.trim();
    if (q === "") return [];
    const raw = this.indexer.search(q, { limit: 200, fuzzy: true });
    return collapseToPrimary(orderByTitleFirst(raw, q)).slice(0, 30);
  }

  renderSuggestion(r: SearchResult, el: HTMLElement): void {
    el.addClass("kvs-quick-suggestion");
    const title = String(r.meta?.["title"] ?? r.location ?? r.id);
    el.createDiv({ cls: "kvs-quick-title", text: title });
    const sub = subtitleFor(r);
    if (sub !== "") el.createDiv({ cls: "kvs-quick-sub", text: sub });
  }

  onChooseSuggestion(r: SearchResult, evt: MouseEvent | KeyboardEvent): void {
    openSearchResult(this.app, r, evt.metaKey || evt.ctrlKey);
  }
}

/** One row per underlying thing: collapse a note's section/annotation docs to a single note entry (highest
 *  ranked wins, since input is already ordered), and dedupe links by URL; keep the rest as-is. */
function collapseToPrimary(results: readonly SearchResult[]): SearchResult[] {
  const seen = new Set<string>();
  const out: SearchResult[] = [];
  for (const r of results) {
    const key = primaryKey(r);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(r);
  }
  return out;
}

function primaryKey(r: SearchResult): string {
  if (r.source === "note" || r.source === "annotation") return `note:${String(r.meta?.["path"] ?? r.id)}`;
  if (r.source === "link") return `link:${String(r.meta?.["url"] ?? r.id)}`;
  return r.id;
}

function subtitleFor(r: SearchResult): string {
  if (r.source === "link") return String(r.meta?.["url"] ?? "");
  const path = String(r.meta?.["path"] ?? "");
  const kind = r.source === "note" ? "" : `${r.source} · `;
  return `${kind}${path}`;
}

/** Open the quick launcher. */
export function openQuickSearch(app: App, indexer: SearchIndexer): void {
  new QuickSearchModal(app, indexer).open();
}
