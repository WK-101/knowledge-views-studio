import { FileView, setIcon, type TFile, type WorkspaceLeaf } from "obsidian";
import {
  decryptFromEnvelope,
  isEncryptedEnvelope,
  readArchive,
  verifyArchive,
  ARCHIVE_EXTENSION,
  type ArchiveContents,
  type BackupPack,
} from "../services/index";
import { resolveColumns } from "../views/index";
import type { ProcessorDeps } from "../codeblock/processor";
import { archiveToPack, packPreviewRows, renderEncryptedLock, restorePackToVault } from "./pack-restore";

export const ARCHIVE_VIEW_TYPE = "kvs-archive";

/** ZIP local-file-header magic ("PK\x03\x04"); distinguishes a plain archive from an envelope. */
function looksLikeZip(bytes: Uint8Array): boolean {
  return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
}

/**
 * Opens a `.kvsarchive` preservation package (a ZIP): shows its manifest, previews the data,
 * verifies file checksums (integrity), and can restore everything — including attachments — to
 * the vault. Read-only; the archive is never modified.
 */
export class ArchiveView extends FileView {
  private bytes: Uint8Array | null = null;
  private contents: ArchiveContents | null = null;
  private pack: BackupPack | null = null;
  private rootEl: HTMLElement | null = null;

  constructor(
    leaf: WorkspaceLeaf,
    private readonly deps: ProcessorDeps,
  ) {
    super(leaf);
    this.allowNoFile = false;
  }

  override getViewType(): string {
    return ARCHIVE_VIEW_TYPE;
  }
  override getDisplayText(): string {
    return this.file?.basename ?? "Archive";
  }
  override getIcon(): string {
    return "archive";
  }
  override canAcceptExtension(extension: string): boolean {
    return extension === ARCHIVE_EXTENSION;
  }

  override async onOpen(): Promise<void> {
    this.rootEl = this.contentEl.createDiv({ cls: "kvs-backup" });
  }

  override async onLoadFile(file: TFile): Promise<void> {
    const buffer = await this.app.vault.readBinary(file);
    const raw = new Uint8Array(buffer);
    if (!looksLikeZip(raw)) {
      const text = new TextDecoder().decode(raw);
      if (isEncryptedEnvelope(text) && this.rootEl) {
        renderEncryptedLock(this.rootEl, async (password) => {
          try {
            const zip = await decryptFromEnvelope(text, password);
            if (!looksLikeZip(zip)) return false;
            this.loadZip(zip);
            this.render();
            return this.pack !== null;
          } catch {
            return false;
          }
        });
        return;
      }
    }
    this.loadZip(raw);
    this.render();
  }

  private loadZip(bytes: Uint8Array): void {
    this.bytes = bytes;
    this.contents = readArchive(bytes);
    this.pack = this.contents ? archiveToPack(this.contents) : null;
  }

  override async onUnloadFile(_file: TFile): Promise<void> {
    this.bytes = null;
    this.contents = null;
    this.pack = null;
    this.rootEl?.empty();
  }

  private render(): void {
    const root = this.rootEl;
    if (!root) return;
    root.empty();
    const manifest = this.contents?.manifest ?? null;
    if (!manifest || !this.pack) {
      root.createDiv({ cls: "kvs-empty", text: "This archive couldn't be read." });
      return;
    }
    const pack = this.pack;

    const card = root.createDiv({ cls: "kvs-backup-card" });
    const head = card.createDiv({ cls: "kvs-backup-head" });
    setIcon(head.createSpan({ cls: "kvs-backup-icon" }), "archive");
    const titles = head.createDiv({ cls: "kvs-backup-titles" });
    titles.createDiv({ cls: "kvs-backup-eyebrow", text: "Archival package" });
    titles.createDiv({ cls: "kvs-backup-title", text: manifest.view.name });

    const when = manifest.createdAt ? new Date(manifest.createdAt).toLocaleString() : "unknown date";
    card.createDiv({
      cls: "kvs-backup-meta",
      text: `${manifest.counts.rows} rows · ${manifest.counts.columns} columns · ${manifest.counts.attachments} attachments · archived ${when}`,
    });
    card.createDiv({
      cls: "kvs-backup-meta",
      text: "Self-contained ZIP: open data/data.csv in any spreadsheet or data/view.html in any browser.",
    });

    const actions = card.createDiv({ cls: "kvs-backup-actions" });
    const restore = actions.createEl("button", { cls: "mod-cta", text: "Restore to vault" });
    restore.addEventListener("click", () => void restorePackToVault(this.app, pack, false));
    const dataOnly = actions.createEl("button", { text: "Create data note only" });
    dataOnly.addEventListener("click", () => void restorePackToVault(this.app, pack, true));
    const verify = actions.createEl("button", { text: "Verify integrity" });
    const verifyResult = card.createDiv({ cls: "kvs-backup-verify" });
    verify.addEventListener("click", () => void this.verify(verifyResult));

    root.createDiv({ cls: "kvs-backup-preview-label", text: "Data preview" });
    const preview = root.createDiv({ cls: "kvs-backup-preview" });
    this.renderPreview(preview, pack);
  }

  private async verify(target: HTMLElement): Promise<void> {
    if (!this.bytes) return;
    target.empty();
    target.setText("Verifying checksums…");
    const report = await verifyArchive(this.bytes);
    target.empty();
    target.toggleClass("is-ok", report.ok);
    target.toggleClass("is-bad", !report.ok);
    if (report.ok) {
      setIcon(target.createSpan({ cls: "kvs-verify-ic" }), "check-circle");
      target.createSpan({ text: `Integrity verified — all ${report.checked} files match their checksums.` });
      return;
    }
    setIcon(target.createSpan({ cls: "kvs-verify-ic" }), "alert-triangle");
    const problems: string[] = [];
    if (report.mismatched.length > 0) problems.push(`${report.mismatched.length} changed (${report.mismatched.join(", ")})`);
    if (report.missing.length > 0) problems.push(`${report.missing.length} missing (${report.missing.join(", ")})`);
    if (report.unlisted.length > 0) problems.push(`${report.unlisted.length} unlisted`);
    target.createSpan({
      text:
        report.checked === 0
          ? "No checksum manifest found — integrity can't be verified."
          : `Integrity check FAILED: ${problems.join("; ")}.`,
    });
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
      viewKey: `archive:${this.file?.path ?? "preview"}`,
      component: this,
      currentSort: [...pack.profile.sort],
      onSortChange: () => {
        /* preview is read-only */
      },
    });
  }
}
