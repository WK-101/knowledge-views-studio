import type { Row, SourceFileMeta } from "../domain/index";
import { createProfile, type Profile } from "./profile/profile";

/**
 * A backup package (extension `.kvspack`) — a frozen, self-contained snapshot of a view. Unlike
 * a `.kvsview` file (which stores only settings and pulls data live from the vault), a pack
 * bundles the complete settings AND the actual row data captured at export time. It is a true
 * backup: it can be opened and restored even if the original source notes are gone.
 */
export const KVS_PACK_EXTENSION = "kvspack";

export interface PackColumn {
  readonly name: string;
  readonly label: string;
  readonly typeId: string;
}

/** A bundled file (image / attachment / any embed) captured from the view, base64-encoded. */
export interface PackAsset {
  /** The exact embed reference this asset came from, e.g. "![[diagram.png]]" or "![](url)". */
  readonly ref: string;
  readonly kind: "internal" | "external";
  /** Filename to write on restore (original name for vault files, generated for URLs). */
  readonly name: string;
  readonly mime: string;
  /** Base64-encoded bytes (no data: prefix). */
  readonly data: string;
}

/** One captured row: its data cells plus the source-file metadata it came from. */
export interface PackRow {
  readonly cells: Record<string, string>;
  readonly file: SourceFileMeta;
}

export interface BackupPack {
  readonly kvsPack: number;
  readonly exportedAt: string;
  readonly generator: string;
  readonly view: { readonly name: string; readonly type: string };
  readonly source: { readonly folders: string[]; readonly extractors: string[] };
  readonly rowCount: number;
  readonly profile: Profile;
  readonly columns: PackColumn[];
  readonly rows: PackRow[];
  /** Bundled files embedded in the view (images, PDFs, attachments) so the backup is complete. */
  readonly assets: PackAsset[];
}

/** Assemble a backup package from a view's profile, its resolved columns, rows and bundled assets. */
export function buildBackupPack(
  profile: Profile,
  columns: readonly PackColumn[],
  rows: readonly Row[],
  generator: string,
  assets: readonly PackAsset[] = [],
): BackupPack {
  return {
    kvsPack: 1,
    exportedAt: new Date().toISOString(),
    generator,
    view: { name: profile.name, type: profile.view.type },
    source: { folders: [...profile.scope.folders], extractors: [...profile.extractors] },
    rowCount: rows.length,
    profile,
    columns: columns.map((c) => ({ name: c.name, label: c.label, typeId: c.typeId })),
    rows: rows.map((r) => ({ cells: { ...r.cells }, file: { ...r.file } })),
    assets: [...assets],
  };
}

export function serializeBackupPack(pack: BackupPack): string {
  return `${JSON.stringify(pack, null, 2)}\n`;
}

const coerceFile = (raw: unknown): SourceFileMeta => {
  const f = (raw ?? {}) as Partial<SourceFileMeta>;
  return {
    filePath: String(f.filePath ?? ""),
    fileName: String(f.fileName ?? ""),
    folderPath: String(f.folderPath ?? ""),
    createdMs: Number(f.createdMs ?? 0),
    modifiedMs: Number(f.modifiedMs ?? 0),
    sizeBytes: Number(f.sizeBytes ?? 0),
  };
};

/** Parse a `.kvspack` file into a normalized package; returns null when it isn't valid. */
export function parseBackupPack(text: string): BackupPack | null {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  let raw: unknown;
  try {
    raw = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (typeof raw !== "object" || raw === null) return null;
  const obj = raw as Record<string, unknown>;
  if (typeof obj.profile !== "object" || obj.profile === null) return null;
  if (!Array.isArray(obj.rows) || !Array.isArray(obj.columns)) return null;

  const profile = createProfile(obj.profile as Partial<Profile>);
  const columns: PackColumn[] = obj.columns
    .filter((c): c is Record<string, unknown> => typeof c === "object" && c !== null && typeof (c as { name?: unknown }).name === "string")
    .map((c) => ({ name: String(c.name), label: String(c.label ?? c.name), typeId: String(c.typeId ?? "text") }));
  const rows: PackRow[] = obj.rows
    .filter((r): r is Record<string, unknown> => typeof r === "object" && r !== null)
    .map((r) => {
      const cellsRaw = (r.cells ?? {}) as Record<string, unknown>;
      const cells: Record<string, string> = {};
      for (const [k, v] of Object.entries(cellsRaw)) cells[k] = String(v ?? "");
      return { cells, file: coerceFile(r.file) };
    });

  const view = (obj.view && typeof obj.view === "object" ? obj.view : {}) as { name?: unknown; type?: unknown };
  const source = (obj.source && typeof obj.source === "object" ? obj.source : {}) as { folders?: unknown; extractors?: unknown };
  const assets: PackAsset[] = Array.isArray(obj.assets)
    ? obj.assets
        .filter((a): a is Record<string, unknown> => typeof a === "object" && a !== null && typeof (a as { data?: unknown }).data === "string")
        .map((a) => ({
          ref: String(a.ref ?? ""),
          kind: a.kind === "external" ? "external" : "internal",
          name: String(a.name ?? "asset"),
          mime: String(a.mime ?? "application/octet-stream"),
          data: String(a.data ?? ""),
        }))
    : [];
  return {
    kvsPack: typeof obj.kvsPack === "number" ? obj.kvsPack : 1,
    exportedAt: typeof obj.exportedAt === "string" ? obj.exportedAt : "",
    generator: typeof obj.generator === "string" ? obj.generator : "",
    view: { name: String(view.name ?? profile.name), type: String(view.type ?? profile.view.type) },
    source: {
      folders: Array.isArray(source.folders) ? source.folders.map(String) : [],
      extractors: Array.isArray(source.extractors) ? source.extractors.map(String) : [],
    },
    rowCount: typeof obj.rowCount === "number" ? obj.rowCount : rows.length,
    profile,
    columns,
    rows,
    assets,
  };
}

/** True for image MIME types (previewable inline as data URLs). */
export function isImageMime(mime: string): boolean {
  return /^image\//i.test(mime);
}

/** A base64 asset as a data URL, for inline rendering. */
export function assetDataUrl(asset: PackAsset): string {
  return `data:${asset.mime || "application/octet-stream"};base64,${asset.data}`;
}

/**
 * Rewrite a cell for restore: external `![](url)` embeds are pointed at the restored local
 * attachment files (`![[name]]`). Internal `![[file]]` embeds are left as-is — they resolve to
 * the attachment files written back under their original names.
 */
export function restoreCellText(text: string, assets: readonly PackAsset[]): string {
  let out = text;
  for (const asset of assets) {
    if (asset.kind === "external") out = out.split(asset.ref).join(`![[${asset.name}]]`);
  }
  return out;
}

/** Rewrite a cell for preview: inline image embeds as data URLs so they show without sources. */
export function previewCellText(text: string, assets: readonly PackAsset[]): string {
  const trimmed = text.trim();
  // Image columns often store just a bare filename — swap the whole cell for a data URL.
  for (const asset of assets) {
    if (isImageMime(asset.mime) && trimmed !== "" && trimmed === asset.name) {
      return `![](${assetDataUrl(asset)})`;
    }
  }
  let out = text;
  for (const asset of assets) {
    if (isImageMime(asset.mime)) out = out.split(asset.ref).join(`![](${assetDataUrl(asset)})`);
  }
  return out;
}

/** Reconstruct display Rows from a pack (for rendering or restoring); provenance is synthetic. */
export function packRowsToRows(pack: BackupPack): Row[] {
  return pack.rows.map((r) => ({
    cells: r.cells,
    file: r.file,
    provenance: { filePath: r.file.filePath, extractor: "snapshot", locator: {}, fingerprint: "" },
  }));
}
