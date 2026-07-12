import { strFromU8, strToU8, unzipSync, zipSync } from "fflate";
import { getField, type Row } from "../domain/index";
import { buildCsv, buildExportTable } from "./export/export-format";
import type { PackColumn } from "./backup-pack";

/**
 * An archival-grade preservation package (extension `.kvsarchive`). Unlike a `.kvspack` (a single
 * JSON file for everyday transfer), this is a self-contained ZIP laid out for long-term storage,
 * inspired by the Library of Congress BagIt packaging convention (RFC 8493):
 *
 *   README.txt              plain-text description of the archive and its layout
 *   manifest.json           format, tool + version, date, schema, counts, provenance
 *   data/data.csv           all rows as CSV — open in any spreadsheet, forever
 *   data/data.json          all rows as JSON — machine-readable, no proprietary schema
 *   data/view.html          human-readable rendering — open in any browser, forever
 *   settings/views.json     the view settings (a .kvsview document)
 *   attachments/…           the real embedded files (images, PDFs, …), not base64
 *   checksums-sha256.txt    a SHA-256 per file, so integrity can be verified
 *
 * Every data format is open and redundant (machine- and human-readable), attachments are real
 * files any tool can extract, and checksums make bit-rot detectable — the requirements for a
 * preservation master rather than a delivery format.
 */
export const ARCHIVE_EXTENSION = "kvsarchive";
export const ARCHIVE_FORMAT_VERSION = 1;

/** An embedded file referenced by the view (metadata only; bytes live under attachments/). */
export interface ArchiveEmbed {
  readonly ref: string;
  readonly kind: "internal" | "external";
  readonly name: string;
  readonly mime: string;
}

export interface ArchiveManifest {
  readonly format: "kvs-archive";
  readonly formatVersion: number;
  readonly specification: string;
  readonly generator: string;
  readonly createdAt: string;
  readonly view: { readonly name: string; readonly type: string };
  readonly source: { readonly folders: string[]; readonly extractors: string[] };
  readonly counts: { readonly rows: number; readonly columns: number; readonly attachments: number };
  readonly columns: PackColumn[];
  readonly embeds: ArchiveEmbed[];
  readonly payload: Record<string, string>;
}

const esc = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

const isImage = (mime: string): boolean => /^image\//i.test(mime);

/** Render one cell to HTML: image embeds → <img>, other embeds → links, else escaped text. */
function cellHtml(value: string, embeds: readonly ArchiveEmbed[]): string {
  const trimmed = value.trim();
  for (const e of embeds) {
    if (isImage(e.mime) && trimmed === e.name) return `<img src="../attachments/${esc(e.name)}" alt="${esc(e.name)}" />`;
  }
  let out = value;
  for (const e of embeds) {
    const rel = `../attachments/${e.name}`;
    const replacement = isImage(e.mime)
      ? `<img src="${esc(rel)}" alt="${esc(e.name)}" />`
      : `<a href="${esc(rel)}">${esc(e.name)}</a>`;
    out = out.split(e.ref).join("\u0000IMG\u0000" + replacement + "\u0000IMG\u0000");
  }
  // Escape the non-embed text, then restore the embed HTML and line breaks.
  return out
    .split("\u0000IMG\u0000")
    .map((part, i) => (i % 2 === 1 ? part : esc(part).replace(/<br\s*\/?>/gi, "<br />").replace(/\n/g, "<br />")))
    .join("");
}

/** A self-contained, human-readable HTML rendering of the view for the archive. */
export function buildArchiveHtml(
  title: string,
  exportedAt: string,
  columns: readonly PackColumn[],
  rows: readonly Row[],
  embeds: readonly ArchiveEmbed[],
): string {
  const head = columns.map((c) => `<th>${esc(c.label)}</th>`).join("");
  const body = rows
    .map((row) => {
      const tds = columns.map((c) => `<td>${cellHtml(getField(row, c.name), embeds)}</td>`).join("");
      return `<tr>${tds}</tr>`;
    })
    .join("");
  const when = exportedAt ? new Date(exportedAt).toLocaleString() : "unknown date";
  return (
    `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8" />` +
    `<meta name="viewport" content="width=device-width, initial-scale=1" />` +
    `<title>${esc(title)}</title>` +
    `<style>` +
    `body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;margin:2rem;color:#1a1a1a;}` +
    `h1{font-size:1.5rem;margin:0 0 .25rem;}` +
    `.meta{color:#666;font-size:.85rem;margin-bottom:1.25rem;}` +
    `table{border-collapse:collapse;width:100%;font-size:.9rem;}` +
    `th,td{border:1px solid #ccc;padding:6px 9px;text-align:left;vertical-align:top;}` +
    `thead th{background:#f2f3f5;}` +
    `tbody tr:nth-child(even){background:#fafafa;}` +
    `img{max-height:120px;max-width:100%;height:auto;}` +
    `a{color:#3a5bd9;}` +
    `</style></head><body>` +
    `<h1>${esc(title)}</h1>` +
    `<div class="meta">${rows.length} rows · ${columns.length} columns · archived ${esc(when)} · Knowledge Views Studio backup</div>` +
    `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>` +
    `</body></html>`
  );
}

/** All rows as pretty JSON (cells + source-file metadata only). */
export function buildRowsJson(rows: readonly Row[]): string {
  return `${JSON.stringify(rows.map((r) => ({ cells: r.cells, file: r.file })), null, 2)}\n`;
}

/** The plain-text README that makes the archive self-describing. */
export function buildArchiveReadme(manifest: ArchiveManifest): string {
  const when = manifest.createdAt ? new Date(manifest.createdAt).toLocaleString() : "unknown date";
  return [
    `KNOWLEDGE VIEWS STUDIO — ARCHIVAL PACKAGE`,
    ``,
    `View:        ${manifest.view.name} (${manifest.view.type})`,
    `Created:     ${when}`,
    `Generated by:${manifest.generator}`,
    `Contents:    ${manifest.counts.rows} rows, ${manifest.counts.columns} columns, ${manifest.counts.attachments} attachments`,
    ``,
    `This is a self-contained, long-term preservation package (a ZIP archive). It is designed to`,
    `remain usable for decades and to be readable WITHOUT this plugin or any special software.`,
    ``,
    `LAYOUT`,
    `  README.txt            This file.`,
    `  manifest.json         Machine-readable description: format, tool, date, schema, counts.`,
    `  data/data.csv         Every row as CSV. Open in Excel, Numbers, Google Sheets, or any editor.`,
    `  data/data.json        Every row as JSON. For programs and scripts.`,
    `  data/view.html        A human-readable rendering. Open in any web browser.`,
    `  settings/views.json   The view's settings (a Knowledge Views ".kvsview" document).`,
    `  attachments/          The embedded files (images, PDFs, …) as real files.`,
    `  checksums-sha256.txt  A SHA-256 hash for every file, to verify nothing has changed or rotted.`,
    ``,
    `HOW TO READ IT`,
    `  • To read the data with no software of ours: unzip this file and open data/data.csv.`,
    `  • To see it as it looked: open data/view.html in a browser (images load from attachments/).`,
    `  • To restore it into Obsidian: open this file with Knowledge Views Studio and choose Restore.`,
    ``,
    `INTEGRITY`,
    `  Verify with the checksums file. On macOS/Linux, from inside the unzipped folder:`,
    `      shasum -a 256 -c checksums-sha256.txt`,
    `  Keep more than one copy, in more than one place (see the 3-2-1 backup rule).`,
    ``,
  ].join("\n");
}

/** BagIt-style checksum manifest: "<sha256-hex>  <relative/path>" per line, path-sorted. */
export function buildChecksumsFile(entries: readonly { readonly path: string; readonly hex: string }[]): string {
  return (
    [...entries]
      .sort((a, b) => a.path.localeCompare(b.path))
      .map((e) => `${e.hex}  ${e.path}`)
      .join("\n") + "\n"
  );
}

export function parseChecksumsFile(text: string): { path: string; hex: string }[] {
  const out: { path: string; hex: string }[] = [];
  for (const line of text.split(/\r?\n/)) {
    const m = /^([0-9a-fA-F]{64})\s+(.+)$/.exec(line.trim());
    if (m) out.push({ hex: m[1]!.toLowerCase(), path: m[2]!.trim() });
  }
  return out;
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  const buf = new ArrayBuffer(data.byteLength);
  new Uint8Array(buf).set(data);
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Everything needed to assemble an archive; the caller supplies content + attachment bytes. */
export interface ArchiveInput {
  readonly manifest: ArchiveManifest;
  readonly readme: string;
  readonly csv: string;
  readonly rowsJson: string;
  readonly html: string;
  readonly settingsJson: string;
  readonly attachments: readonly { readonly name: string; readonly bytes: Uint8Array }[];
}

/** Build the CSV/JSON/HTML/manifest/README + attachments + checksums into a ZIP archive. */
export async function assembleArchive(input: ArchiveInput): Promise<Uint8Array> {
  const files: Record<string, Uint8Array> = {
    "README.txt": strToU8(input.readme),
    "manifest.json": strToU8(`${JSON.stringify(input.manifest, null, 2)}\n`),
    "data/data.csv": strToU8(input.csv),
    "data/data.json": strToU8(input.rowsJson),
    "data/view.html": strToU8(input.html),
    "settings/views.json": strToU8(input.settingsJson),
  };
  const seen = new Set<string>();
  for (const a of input.attachments) {
    if (seen.has(a.name)) continue; // same-named files stored once
    seen.add(a.name);
    files[`attachments/${a.name}`] = a.bytes;
  }
  const entries = await Promise.all(
    Object.entries(files).map(async ([path, bytes]) => ({ path, hex: await sha256Hex(bytes) })),
  );
  files["checksums-sha256.txt"] = strToU8(buildChecksumsFile(entries));
  return zipSync(files, { level: 6 });
}

export interface ArchiveContents {
  readonly manifest: ArchiveManifest | null;
  readonly settingsJson: string;
  readonly rowsJson: string;
  readonly checksumsText: string;
  readonly attachments: Map<string, Uint8Array>;
}

/** Unzip an archive and pull out its parsed parts (synchronous). */
export function readArchive(zipBytes: Uint8Array): ArchiveContents | null {
  let unzipped: Record<string, Uint8Array>;
  try {
    unzipped = unzipSync(zipBytes);
  } catch {
    return null;
  }
  const text = (path: string): string => (unzipped[path] ? strFromU8(unzipped[path]!) : "");
  let manifest: ArchiveManifest | null = null;
  try {
    const raw = text("manifest.json");
    manifest = raw ? (JSON.parse(raw) as ArchiveManifest) : null;
  } catch {
    manifest = null;
  }
  const attachments = new Map<string, Uint8Array>();
  for (const [path, bytes] of Object.entries(unzipped)) {
    if (path.startsWith("attachments/") && path.length > "attachments/".length) {
      attachments.set(path.slice("attachments/".length), bytes);
    }
  }
  return {
    manifest,
    settingsJson: text("settings/views.json"),
    rowsJson: text("data/data.json"),
    checksumsText: text("checksums-sha256.txt"),
    attachments,
  };
}

export interface VerifyReport {
  readonly ok: boolean;
  readonly checked: number;
  readonly mismatched: string[];
  readonly missing: string[];
  readonly unlisted: string[];
}

/** Recompute every file's SHA-256 and compare against checksums-sha256.txt. */
export async function verifyArchive(zipBytes: Uint8Array): Promise<VerifyReport> {
  let unzipped: Record<string, Uint8Array>;
  try {
    unzipped = unzipSync(zipBytes);
  } catch {
    return { ok: false, checked: 0, mismatched: [], missing: [], unlisted: [] };
  }
  const listed = parseChecksumsFile(unzipped["checksums-sha256.txt"] ? strFromU8(unzipped["checksums-sha256.txt"]!) : "");
  const listedPaths = new Set(listed.map((e) => e.path));
  const mismatched: string[] = [];
  const missing: string[] = [];
  for (const entry of listed) {
    const bytes = unzipped[entry.path];
    if (!bytes) {
      missing.push(entry.path);
      continue;
    }
    const hex = await sha256Hex(bytes);
    if (hex !== entry.hex) mismatched.push(entry.path);
  }
  const unlisted = Object.keys(unzipped).filter((p) => p !== "checksums-sha256.txt" && !listedPaths.has(p));
  return { ok: mismatched.length === 0 && missing.length === 0, checked: listed.length, mismatched, missing, unlisted };
}

/** Reusable helper for callers building the CSV from rows + columns. */
export function archiveCsv(rows: readonly Row[], columns: readonly PackColumn[]): string {
  return buildCsv(buildExportTable(rows, columns.map((c) => ({ name: c.name, label: c.name, typeId: c.typeId })), false));
}
