import { TFile, requestUrl, type App } from "obsidian";
import {
  archiveCsv,
  assembleArchive,
  buildArchiveHtml,
  buildArchiveReadme,
  buildBackupPack,
  buildRowsJson,
  createProfile,
  encryptToEnvelope,
  serializeBackupPack,
  serializeViewDoc,
  ARCHIVE_EXTENSION,
  ARCHIVE_FORMAT_VERSION,
  KVS_PACK_EXTENSION,
  type ArchiveEmbed,
  type ArchiveManifest,
  type PackAsset,
  type Profile,
} from "../services/index";
import { extractImageEmbeds } from "../util/markdown";
import { getField, type Row } from "../domain/index";
import { resolveColumns } from "../views/index";
import type { ProcessorDeps } from "../codeblock/processor";
import type { BackupExportOptions } from "./backup-export-modal";

const ASSET_MIME: Record<string, string> = {
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  gif: "image/gif",
  webp: "image/webp",
  svg: "image/svg+xml",
  bmp: "image/bmp",
  pdf: "application/pdf",
  mp3: "audio/mpeg",
  wav: "audio/wav",
  ogg: "audio/ogg",
  m4a: "audio/mp4",
  mp4: "video/mp4",
  webm: "video/webm",
  mov: "video/quicktime",
  json: "application/json",
  txt: "text/plain",
  md: "text/markdown",
  zip: "application/zip",
};
const extFromPath = (path: string): string => {
  const clean = path.split(/[?#]/)[0] ?? "";
  const dot = clean.lastIndexOf(".");
  return dot >= 0 ? clean.slice(dot + 1).toLowerCase() : "";
};
const mimeFromExt = (ext: string): string => ASSET_MIME[ext.toLowerCase()] ?? "application/octet-stream";
const safeAssetName = (name: string): string => name.replace(/[\\/:*?"<>|]/g, "-").trim() || "asset";
function bytesToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  return btoa(binary);
}

/** Resolve an embed to raw bytes + name + mime, reading vault files or fetching URLs. */
async function resolveEmbedBytes(
  app: App,
  embed: string,
): Promise<{ ref: string; kind: "internal" | "external"; name: string; mime: string; bytes: ArrayBuffer } | null> {
  try {
    const internal = /^!\[\[(.+?)\]\]$/.exec(embed);
    if (internal) {
      const link = (internal[1] ?? "").split("|")[0]!.split("#")[0]!.trim();
      const file = app.metadataCache.getFirstLinkpathDest(link, "");
      if (!(file instanceof TFile)) return null;
      return { ref: embed, kind: "internal", name: safeAssetName(file.name), mime: mimeFromExt(file.extension), bytes: await app.vault.readBinary(file) };
    }
    const external = /^!\[[^\]]*\]\((.+?)\)$/.exec(embed);
    if (external) {
      const target = (external[1] ?? "").trim();
      if (/^https?:\/\//i.test(target)) {
        const res = await requestUrl({ url: target });
        const clean = target.split(/[?#]/)[0] ?? "";
        let name = clean.split("/").pop() || "asset";
        const ext = extFromPath(target);
        if (!name.includes(".") && ext) name += `.${ext}`;
        const mime = res.headers?.["content-type"]?.split(";")[0]?.trim() || mimeFromExt(ext);
        return { ref: embed, kind: "external", name: safeAssetName(name), mime, bytes: res.arrayBuffer };
      }
      const file = app.metadataCache.getFirstLinkpathDest(target, "") ?? app.vault.getAbstractFileByPath(target);
      if (file instanceof TFile) {
        return { ref: embed, kind: "internal", name: safeAssetName(file.name), mime: mimeFromExt(file.extension), bytes: await app.vault.readBinary(file) };
      }
    }
  } catch {
    // Best-effort: skip unreadable vault files or unfetchable URLs.
  }
  return null;
}

function collectEmbedRefs(rows: readonly Row[], columns: readonly { name: string; typeId: string }[]): Set<string> {
  const refs = new Set<string>();
  for (const row of rows) {
    for (const value of Object.values(row.cells)) for (const e of extractImageEmbeds(value)) refs.add(e);
    for (const col of columns) {
      if (col.typeId !== "image") continue;
      const value = getField(row, col.name).trim();
      if (value !== "" && extractImageEmbeds(value).length === 0) refs.add(`![[${value}]]`);
    }
  }
  return refs;
}

async function ensureFolder(app: App, folder: string): Promise<void> {
  const parts = folder.split("/").filter(Boolean);
  let path = "";
  for (const part of parts) {
    path = path ? `${path}/${part}` : part;
    if (!app.vault.getAbstractFileByPath(path)) await app.vault.createFolder(path).catch(() => undefined);
  }
}

async function uniqueVaultPath(app: App, path: string): Promise<string> {
  if (!app.vault.getAbstractFileByPath(path)) return path;
  const dot = path.lastIndexOf(".");
  const stem = dot >= 0 ? path.slice(0, dot) : path;
  const ext = dot >= 0 ? path.slice(dot) : "";
  for (let i = 2; i < 9999; i++) {
    const candidate = `${stem} ${i}${ext}`;
    if (!app.vault.getAbstractFileByPath(candidate)) return candidate;
  }
  return `${stem} ${Date.now()}${ext}`;
}

/** Query the rows for an export, honouring the row-scope option. */
async function queryRows(deps: ProcessorDeps, profile: Profile, o: BackupExportOptions, search: string, page: number): Promise<Row[]> {
  const result =
    o.scope === "page" && profile.pageSize
      ? await deps.dataService.query(profile, { search, page })
      : await deps.dataService.query({ ...profile, pageSize: null }, { search });
  return result.rows;
}

export interface BackupProgress {
  (message: string): void;
}

/**
 * Export a single view to a `.kvspack`/`.kvsarchive` file per the options, optionally encrypted.
 * Returns the created file, or null on failure. `open` controls whether the file is opened.
 */
export async function exportViewBackup(
  app: App,
  deps: ProcessorDeps,
  profile: Profile,
  o: BackupExportOptions,
  search: string,
  page: number,
  open: boolean,
  progress?: BackupProgress,
): Promise<TFile | null> {
  const rows = await queryRows(deps, profile, o, search, page);
  const columns = resolveColumns(profile, rows).map((c) => ({ name: c.name, label: c.label, typeId: c.typeId }));

  const packAssets: PackAsset[] = [];
  const archiveAttachments: { name: string; bytes: Uint8Array }[] = [];
  const embeds: ArchiveEmbed[] = [];
  if (o.includeAttachments) {
    const refs = collectEmbedRefs(rows, columns);
    if (refs.size > 0) progress?.(`Bundling ${refs.size} attachment(s)…`);
    const seen = new Set<string>();
    for (const ref of refs) {
      const res = await resolveEmbedBytes(app, ref);
      if (!res) continue;
      if (res.kind === "external" && !o.includeExternal) continue;
      const bytes = new Uint8Array(res.bytes);
      embeds.push({ ref, kind: res.kind, name: res.name, mime: res.mime });
      packAssets.push({ ref, kind: res.kind, name: res.name, mime: res.mime, data: bytesToBase64(res.bytes) });
      if (!seen.has(res.name)) {
        seen.add(res.name);
        archiveAttachments.push({ name: res.name, bytes });
      }
    }
  }

  let payload: Uint8Array;
  const ext = o.format === "pack" ? KVS_PACK_EXTENSION : ARCHIVE_EXTENSION;
  if (o.format === "pack") {
    payload = new TextEncoder().encode(serializeBackupPack(buildBackupPack(profile, columns, rows, "Knowledge Views Studio", packAssets)));
  } else {
    const settingsJson = serializeViewDoc({ views: [createProfile({ ...profile })], activeView: profile.id });
    const manifest: ArchiveManifest = {
      format: "kvs-archive",
      formatVersion: ARCHIVE_FORMAT_VERSION,
      specification: "BagIt-inspired preservation package (RFC 8493)",
      generator: "Knowledge Views Studio",
      createdAt: new Date().toISOString(),
      view: { name: profile.name, type: profile.view.type },
      source: { folders: [...profile.scope.folders], extractors: [...profile.extractors] },
      counts: { rows: rows.length, columns: columns.length, attachments: archiveAttachments.length },
      columns,
      embeds,
      payload: {
        "data/data.csv": "All rows as CSV",
        "data/data.json": "All rows as JSON",
        "data/view.html": "Human-readable rendering",
        "settings/views.json": "View settings (.kvsview document)",
        "attachments/": "Embedded files (images, PDFs, …)",
      },
    };
    payload = await assembleArchive({
      manifest,
      readme: buildArchiveReadme(manifest),
      csv: archiveCsv(rows, columns),
      rowsJson: buildRowsJson(rows),
      html: buildArchiveHtml(profile.name, manifest.createdAt, columns, rows, embeds),
      settingsJson,
      attachments: archiveAttachments,
    });
  }

  const outText = o.encrypt ? await encryptToEnvelope(payload, o.password) : null;

  let base = o.filename.replace(/[\\/:*?"<>|]/g, "-").trim() || "View";
  if (o.dateStamp) base += ` ${new Date().toISOString().slice(0, 10)}`;
  const folder = o.folder.trim().replace(/^\/+|\/+$/g, "");
  if (folder) await ensureFolder(app, folder);
  const path = await uniqueVaultPath(app, folder ? `${folder}/${base}.${ext}` : `${base}.${ext}`);

  let file: TFile | null;
  if (outText !== null) file = await app.vault.create(path, outText);
  else if (o.format === "pack") file = await app.vault.create(path, new TextDecoder().decode(payload));
  else file = await app.vault.createBinary(path, payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength) as ArrayBuffer);

  if (open && file instanceof TFile) void app.workspace.getLeaf(true).openFile(file);
  return file instanceof TFile ? file : null;
}

export interface BackupAllReport {
  folder: string;
  ok: number;
  failed: string[];
}

/** Back up every stored view into a dated subfolder using the given options. */
export async function backupAllViews(app: App, deps: ProcessorDeps, o: BackupExportOptions, progress?: BackupProgress): Promise<BackupAllReport> {
  const profiles = deps.store.listProfiles();
  const stamp = new Date().toISOString().slice(0, 10);
  const parent = (o.folder.trim().replace(/^\/+|\/+$/g, "") || "KVS Backups") + `/${stamp}`;
  await ensureFolder(app, parent);
  let ok = 0;
  const failed: string[] = [];
  let i = 0;
  for (const profile of profiles) {
    i++;
    progress?.(`Backing up ${i}/${profiles.length}: ${profile.name}…`);
    try {
      const perOptions: BackupExportOptions = { ...o, scope: "all", folder: parent, filename: profile.name, dateStamp: false };
      const file = await exportViewBackup(app, deps, profile, perOptions, "", 0, false);
      if (file) ok++;
      else failed.push(profile.name);
    } catch {
      failed.push(profile.name);
    }
  }
  return { folder: parent, ok, failed };
}
