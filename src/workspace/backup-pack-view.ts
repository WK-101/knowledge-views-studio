import { TextFileView, setIcon, type WorkspaceLeaf } from "obsidian";
import { decryptFromEnvelope, isEncryptedEnvelope, parseBackupPack, KVS_PACK_EXTENSION, type BackupPack } from "../services/index";
import { resolveColumns } from "../views/index";
import type { ProcessorDeps } from "../codeblock/processor";
import { packPreviewRows, renderEncryptedLock, restorePackToVault } from "./pack-restore";

export const BACKUP_VIEW_TYPE = "kvs-backup-pack";

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Opens a `.kvspack` backup package: shows what it contains, previews the frozen data (with
 * bundled images inlined so it renders even if the sources are gone), and can restore it to the
 * vault. The file is read-only — a backup is never silently rewritten.
 */
export class BackupPackView extends TextFileView {
  private pack: BackupPack | null = null;
  private rootEl: HTMLElement | null = null;
  private raw = "";
  private encrypted = false;

  constructor(
    leaf: WorkspaceLeaf,
    private readonly deps: ProcessorDeps,
  ) {
    super(leaf);
  }

  override getViewType(): string {
    return BACKUP_VIEW_TYPE;
  }
  override getDisplayText(): string {
    return this.file?.basename ?? this.pack?.view.name ?? "Backup package";
  }
  override getIcon(): string {
    return "package";
  }
  override canAcceptExtension(extension: string): boolean {
    return extension === KVS_PACK_EXTENSION;
  }

  override getViewData(): string {
    return this.raw; // read-only snapshot
  }
  override setViewData(data: string, _clear: boolean): void {
    this.raw = data;
    this.encrypted = isEncryptedEnvelope(data);
    this.pack = this.encrypted ? null : parseBackupPack(data);
    if (this.rootEl) this.paint();
  }
  override clear(): void {
    this.pack = null;
    this.raw = "";
    this.encrypted = false;
    this.rootEl?.empty();
  }

  override async onOpen(): Promise<void> {
    this.rootEl = this.contentEl.createDiv({ cls: "kvs-backup" });
    this.paint();
  }

  private paint(): void {
    if (!this.rootEl) return;
    if (this.encrypted && !this.pack) {
      renderEncryptedLock(this.rootEl, async (password) => {
        try {
          const bytes = await decryptFromEnvelope(this.raw, password);
          this.pack = parseBackupPack(new TextDecoder().decode(bytes));
          this.encrypted = false;
          this.render();
          return this.pack !== null;
        } catch {
          return false;
        }
      });
      return;
    }
    this.render();
  }

  private render(): void {
    const root = this.rootEl;
    if (!root) return;
    root.empty();
    const pack = this.pack;
    if (!pack) {
      root.createDiv({ cls: "kvs-empty", text: "This backup package couldn't be read." });
      return;
    }

    const card = root.createDiv({ cls: "kvs-backup-card" });
    const head = card.createDiv({ cls: "kvs-backup-head" });
    setIcon(head.createSpan({ cls: "kvs-backup-icon" }), "package");
    const titles = head.createDiv({ cls: "kvs-backup-titles" });
    titles.createDiv({ cls: "kvs-backup-eyebrow", text: "Backup package" });
    titles.createDiv({ cls: "kvs-backup-title", text: pack.view.name });

    const when = pack.exportedAt ? new Date(pack.exportedAt).toLocaleString() : "unknown date";
    card.createDiv({
      cls: "kvs-backup-meta",
      text: `${pack.rowCount} rows · ${pack.columns.length} columns · ${pack.assets.length} files · ${pack.view.type} view · exported ${when}`,
    });
    if (pack.source.folders.length > 0) {
      card.createDiv({ cls: "kvs-backup-meta", text: `Source: ${pack.source.folders.join(", ")}` });
    }

    const actions = card.createDiv({ cls: "kvs-backup-actions" });
    const restore = actions.createEl("button", { cls: "mod-cta", text: "Restore to vault" });
    restore.addEventListener("click", () => void restorePackToVault(this.app, pack, false));
    const dataOnly = actions.createEl("button", { text: "Create data note only" });
    dataOnly.addEventListener("click", () => void restorePackToVault(this.app, pack, true));
    const verify = actions.createEl("button", { text: "Verify backup" });
    const verifyResult = card.createDiv({ cls: "kvs-backup-verify" });
    verify.addEventListener("click", () => this.verify(verifyResult, pack));

    root.createDiv({ cls: "kvs-backup-preview-label", text: "Data preview" });
    const preview = root.createDiv({ cls: "kvs-backup-preview" });
    this.renderPreview(preview, pack);
  }

  /** Verify the pack is complete and readable: structure, row cells, and every bundled asset. */
  private verify(target: HTMLElement, pack: BackupPack): void {
    target.empty();
    let badAssets = 0;
    let totalBytes = 0;
    for (const asset of pack.assets) {
      try {
        const bin = atob(asset.data);
        if (bin.length === 0) badAssets++;
        else totalBytes += bin.length;
      } catch {
        badAssets++;
      }
    }
    const emptyRows = pack.rows.filter((r) => !r.cells || Object.keys(r.cells).length === 0).length;
    const ok = badAssets === 0;
    target.toggleClass("is-ok", ok);
    target.toggleClass("is-bad", !ok);
    setIcon(target.createSpan({ cls: "kvs-verify-ic" }), ok ? "check-circle" : "alert-triangle");
    if (ok) {
      const size = totalBytes > 0 ? `, ${formatBytes(totalBytes)} of attachments` : "";
      const warn = emptyRows > 0 ? ` (${emptyRows} rows have no data)` : "";
      target.createSpan({
        text: `Backup is readable — ${pack.rowCount} rows, ${pack.columns.length} columns, ${pack.assets.length} attachments${size} all decoded${warn}.`,
      });
    } else {
      target.createSpan({ text: `Problem: ${badAssets} of ${pack.assets.length} bundled attachments could not be decoded.` });
    }
  }

  private renderPreview(container: HTMLElement, pack: BackupPack): void {
    const view = this.deps.views.get("table");
    if (!view) {
      container.setText("No table view is available to preview this data.");
      return;
    }
    const rows = packPreviewRows(pack);
    const columns = resolveColumns(pack.profile, rows);
    const CAP = 300;
    if (rows.length > CAP) {
      container.createDiv({ cls: "kvs-banner", text: `Showing the first ${CAP} of ${rows.length} rows.` });
    }
    const host = container.createDiv({ cls: "kvs-backup-preview-host" });
    view.render({
      container: host,
      result: { rows: rows.slice(0, CAP), groups: null, total: rows.length, gathered: rows.length, page: null },
      profile: { ...pack.profile, view: { type: "table", options: {} } },
      columns,
      cellRenderers: this.deps.cellRenderers,
      app: this.deps.app,
      sourcePath: this.file?.path ?? "",
      viewKey: `backup:${this.file?.path ?? "preview"}`,
      component: this,
      currentSort: [...pack.profile.sort],
      onSortChange: () => {
        /* preview is read-only */
      },
    });
  }
}
