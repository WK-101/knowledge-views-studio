import { Notice, TFile, setIcon, type App } from "obsidian";
import {
  buildExportTable,
  buildMarkdownTable,
  createProfile,
  packRowsToRows,
  parseViewFile,
  previewCellText,
  restoreCellText,
  serializeViewDoc,
  KVS_VIEW_EXTENSION,
  type ArchiveContents,
  type BackupPack,
  type PackAsset,
} from "../services/index";
import type { Row } from "../domain/index";

export function base64ToBytes(b64: string): ArrayBuffer {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  return btoa(binary);
}

/** Rows for a read-only preview: images inlined as data URLs so they render without sources. */
export function packPreviewRows(pack: BackupPack): Row[] {
  return packRowsToRows(pack).map((row) => {
    const cells: Record<string, string> = {};
    for (const [k, v] of Object.entries(row.cells)) cells[k] = previewCellText(v, pack.assets);
    return { ...row, cells };
  });
}

/** Rebuild a BackupPack from an unpacked archive so the viewer can reuse pack rendering/restore. */
export function archiveToPack(contents: ArchiveContents): BackupPack | null {
  const manifest = contents.manifest;
  if (!manifest) return null;
  const profile = parseViewFile(contents.settingsJson) ?? createProfile({ name: manifest.view.name });
  let parsed: unknown = [];
  try {
    parsed = JSON.parse(contents.rowsJson);
  } catch {
    parsed = [];
  }
  const rows = Array.isArray(parsed)
    ? parsed.map((r) => {
        const rr = (r ?? {}) as { cells?: Record<string, string>; file?: unknown };
        const f = (rr.file ?? {}) as Record<string, unknown>;
        return {
          cells: rr.cells ?? {},
          file: {
            filePath: String(f.filePath ?? ""),
            fileName: String(f.fileName ?? ""),
            folderPath: String(f.folderPath ?? ""),
            createdMs: Number(f.createdMs ?? 0),
            modifiedMs: Number(f.modifiedMs ?? 0),
            sizeBytes: Number(f.sizeBytes ?? 0),
          },
        };
      })
    : [];
  const assets: PackAsset[] = [];
  for (const e of manifest.embeds) {
    const bytes = contents.attachments.get(e.name);
    if (bytes) assets.push({ ref: e.ref, kind: e.kind, name: e.name, mime: e.mime, data: bytesToBase64(bytes) });
  }
  return {
    kvsPack: 2,
    exportedAt: manifest.createdAt,
    generator: manifest.generator,
    view: manifest.view,
    source: manifest.source,
    rowCount: manifest.counts.rows,
    profile,
    columns: manifest.columns,
    rows,
    assets,
  };
}

/**
 * Restore a package to the vault: write bundled attachments, a Markdown-table data note, and
 * (unless dataOnly) a matching `.kvsview` file scoped to the restored folder. Shared by the
 * `.kvspack` and `.kvsarchive` viewers.
 */
export async function restorePackToVault(app: App, pack: BackupPack, dataOnly: boolean): Promise<void> {
  const safe = (pack.view.name || "Restored").replace(/[\\/:*?"<>|]/g, "-").trim() || "Restored";
  const parent = "KVS Restored";
  if (!app.vault.getAbstractFileByPath(parent)) await app.vault.createFolder(parent).catch(() => undefined);
  let folder = `${parent}/${safe}`;
  for (let i = 2; app.vault.getAbstractFileByPath(folder); i++) folder = `${parent}/${safe} ${i}`;
  await app.vault.createFolder(folder);

  if (pack.assets.length > 0) {
    const attachments = `${folder}/attachments`;
    await app.vault.createFolder(attachments).catch(() => undefined);
    const written = new Set<string>();
    for (const asset of pack.assets) {
      if (written.has(asset.name)) continue;
      written.add(asset.name);
      try {
        await app.vault.createBinary(`${attachments}/${asset.name}`, base64ToBytes(asset.data));
      } catch {
        // Skip a single unwritable attachment rather than failing the whole restore.
      }
    }
  }

  const rows: Row[] = packRowsToRows(pack).map((row) => {
    const cells: Record<string, string> = {};
    for (const [k, v] of Object.entries(row.cells)) cells[k] = restoreCellText(v, pack.assets);
    return { ...row, cells };
  });
  const table = buildExportTable(
    rows,
    pack.columns.map((c) => ({ name: c.name, label: c.name, typeId: c.typeId })),
    false,
  );
  const when = pack.exportedAt ? new Date(pack.exportedAt).toLocaleString() : "unknown date";
  const md = `# ${pack.view.name}\n\n> Restored from a Knowledge Views backup — ${pack.rowCount} rows, ${pack.assets.length} attachments, exported ${when}.\n\n${buildMarkdownTable(table)}\n`;
  const dataNote = await app.vault.create(`${folder}/${safe} data.md`, md);

  if (dataOnly) {
    new Notice(`Restored data note and ${pack.assets.length} attachment(s) to ${folder}`);
    if (dataNote instanceof TFile) void app.workspace.getLeaf(true).openFile(dataNote);
    return;
  }

  const restoredProfile = createProfile({
    ...pack.profile,
    id: undefined,
    scope: { mode: "folders", folders: [folder], includeSubfolders: true },
    extractors: ["table"],
  });
  const viewFile = await app.vault.create(
    `${folder}/${safe}.${KVS_VIEW_EXTENSION}`,
    serializeViewDoc({ views: [restoredProfile], activeView: restoredProfile.id }),
  );
  new Notice(`Restored view, data and ${pack.assets.length} attachment(s) to ${folder}`);
  if (viewFile instanceof TFile) void app.workspace.getLeaf(true).openFile(viewFile);
}

/** Render a password prompt for an encrypted backup. `onUnlock` returns true on success. */
export function renderEncryptedLock(container: HTMLElement, onUnlock: (password: string) => Promise<boolean>): void {
  container.empty();
  const card = container.createDiv({ cls: "kvs-backup-card kvs-lock" });
  const head = card.createDiv({ cls: "kvs-backup-head" });
  setIcon(head.createSpan({ cls: "kvs-backup-icon" }), "lock");
  const titles = head.createDiv({ cls: "kvs-backup-titles" });
  titles.createDiv({ cls: "kvs-backup-eyebrow", text: "Encrypted" });
  titles.createDiv({ cls: "kvs-backup-title", text: "This backup is password-protected" });
  const row = card.createDiv({ cls: "kvs-lock-row" });
  const input = row.createEl("input", { type: "password", placeholder: "Password" });
  const btn = row.createEl("button", { cls: "mod-cta", text: "Unlock" });
  const err = card.createDiv({ cls: "kvs-lock-err" });
  const attempt = async (): Promise<void> => {
    err.setText("");
    btn.setAttribute("disabled", "true");
    const ok = await onUnlock(input.value);
    if (!ok) {
      err.setText("Incorrect password, or the file is corrupt.");
      btn.removeAttribute("disabled");
      input.select();
    }
  };
  btn.addEventListener("click", () => void attempt());
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") void attempt();
  });
  window.setTimeout(() => input.focus(), 0);
}
