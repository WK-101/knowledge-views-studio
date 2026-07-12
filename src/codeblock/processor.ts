import { MarkdownRenderChild, Notice } from "obsidian";
import { createId } from "../util/id";
import type { Row, SortKey } from "../domain/index";
import { resolveRowDefaults } from "../domain/index";
import type { Profile, ProfileStore } from "../services/index";
import {
  buildClipboardFor,
  createEditingHandlers,
  renderProfile,
  resolveColumns,
  writeClipboard,
  type CopyFormat,
  type EditingHandlers,
  type RenderProfileDeps,
} from "../views/index";
import { parseViewBlock } from "./config";
import { resolveBlockProfile } from "./resolve";

export interface ProcessorDeps extends RenderProfileDeps {
  readonly store: ProfileStore;
}

function findReferenced(store: ProfileStore, ref: string): Profile | undefined {
  return (
    store.getProfile(ref) ??
    store.listProfiles().find((p) => p.name.toLowerCase() === ref.toLowerCase())
  );
}

/**
 * Renders one ```knowledge-view``` block as a live dashboard. As a
 * MarkdownRenderChild it gets a real lifecycle: the data subscription and any
 * DOM listeners are cleaned up automatically when the block leaves the view.
 * When auto-refresh is on and an in-scope note changes, the block re-renders —
 * the live, never-stale delivery the legacy file-export approach could not give.
 */
export class ViewBlockController extends MarkdownRenderChild {
  private profile: Profile;
  private editing?: EditingHandlers;
  private readonly viewKey = createId("block");

  constructor(
    containerEl: HTMLElement,
    source: string,
    private readonly sourcePath: string,
    private readonly deps: ProcessorDeps,
  ) {
    super(containerEl);
    const config = parseViewBlock(source);
    const referenced = config.profile ? findReferenced(deps.store, config.profile) : undefined;
    this.profile = resolveBlockProfile(config, referenced, deps.store.getSettings());
  }

  override onload(): void {
    if (this.deps.store.getSettings().inlineEditing) {
      this.editing = createEditingHandlers(
        this.deps,
        () => void this.rerender(),
        () => resolveRowDefaults(this.profile.columns),
      );
    }
    void this.rerender();
    this.register(
      this.deps.dataService.onChange((change) => {
        const inScope = change.paths.some((path) =>
          this.deps.dataService.affectsScope(path, this.profile.scope),
        );
        if (inScope) void this.rerender();
      }),
    );
  }

  private async rerender(): Promise<void> {
    await renderProfile({
      container: this.containerEl,
      profile: this.profile,
      deps: this.deps,
      component: this,
      sourcePath: this.sourcePath,
      viewKey: this.viewKey,
      maxRows: this.deps.store.getSettings().maxRows,
      onSortChange: (keys: SortKey[]) => {
        this.profile = { ...this.profile, sort: keys };
        void this.rerender();
      },
      ...(this.editing ? { editing: this.editing } : {}),
      ...(this.deps.store.getSettings().enableRowCopy
        ? {
            onCopyRows: (rows: readonly Row[], format?: CopyFormat) => this.copyRows(rows, format),
            copyOnShortcut: this.deps.store.getSettings().copyUseShortcut,
            copyOptions: {
              includeHeader: this.deps.store.getSettings().copyIncludeHeader,
              stripLinks: this.deps.store.getSettings().copyLinkHandling === "text",
              onToggleHeader: () =>
                this.deps.store.updateSettings({ copyIncludeHeader: !this.deps.store.getSettings().copyIncludeHeader }),
              onToggleStripLinks: () =>
                this.deps.store.updateSettings({
                  copyLinkHandling: this.deps.store.getSettings().copyLinkHandling === "text" ? "keep" : "text",
                }),
            },
          }
        : {}),
    });
  }

  private copyRows(rows: readonly Row[], format: CopyFormat = "markdown"): void {
    if (rows.length === 0) return;
    const settings = this.deps.store.getSettings();
    const columns = resolveColumns(this.profile, rows);
    const payload = buildClipboardFor(format, rows, columns, {
      linkHandling: settings.copyLinkHandling,
      includeHeader: settings.copyIncludeHeader,
      includeHtml: settings.copyIncludeHtml,
    });
    void writeClipboard(payload).then((ok) =>
      new Notice(ok ? `Copied ${rows.length} row${rows.length === 1 ? "" : "s"} to the clipboard.` : "Couldn't access the clipboard."),
    );
  }
}
