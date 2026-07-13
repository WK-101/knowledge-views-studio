import { getField, type Row } from "../../domain/index";
import type { Profile } from "../../services/index";
import type { ResolvedColumn } from "../view-model";
import { asString } from "../../util/coerce";

export type LinkHandling = "keep" | "text" | "path";

/** The explicit formats offered by "Copy as…". `markdown` is also the smart Cmd-C default. */
export type CopyFormat = "markdown" | "tsv" | "csv" | "json" | "bullets" | "kvs";

export const COPY_FORMATS: readonly { id: CopyFormat; label: string }[] = [
  { id: "markdown", label: "Markdown table" },
  { id: "tsv", label: "Values only (TSV)" },
  { id: "csv", label: "CSV" },
  { id: "json", label: "JSON" },
  { id: "bullets", label: "Bullet list (per row)" },
  { id: "kvs", label: "KVS rows (re-importable, keeps types)" },
];

export interface RowCopyOptions {
  readonly linkHandling: LinkHandling;
  readonly includeHeader: boolean;
  readonly includeHtml: boolean;
}

export interface ClipboardPayload {
  /** The primary text format (Markdown table, TSV, CSV, JSON, …). */
  readonly plain: string;
  /** An optional rich HTML format, so Word / Docs / Excel get a real table or list. */
  readonly html?: string;
}

/** Rewrite `[[target|alias]]` / `[[target]]` (and `![[…]]` embeds) to display text or the path. */
function transformLinks(value: string, mode: "text" | "path"): string {
  return value.replace(/!?\[\[([^\]]+)\]\]/g, (_match, inner: string) => {
    const bar = inner.indexOf("|");
    const target = (bar === -1 ? inner : inner.slice(0, bar)).trim();
    const alias = bar === -1 ? undefined : inner.slice(bar + 1).trim();
    if (mode === "path") return target;
    if (alias) return alias;
    const base = target.split("/").pop() ?? target;
    return base.replace(/#.*$/, "").replace(/\^.*$/, "").trim();
  });
}

/** For plain-data formats (TSV/CSV/JSON), "keep" wikilinks makes no sense, so fall back to text. */
const dataLinkMode = (mode: LinkHandling): "text" | "path" => (mode === "path" ? "path" : "text");

/** Flatten a value to a single line for non-Markdown formats: <br> and newlines/tabs become spaces. */
const collapseBreaks = (s: string): string => s.replace(/<br\s*\/?>/gi, " ").replace(/[\t\r\n]+/g, " ").replace(/ {2,}/g, " ");

const escapePipe = (s: string): string =>
  s.replace(/\\/g, "\\\\").replace(/\|/g, "\\|").replace(/\r?\n+/g, " ");

const escapeHtml = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function markdownCell(value: string, mode: LinkHandling): string {
  return escapePipe(mode === "keep" ? value : transformLinks(value, mode));
}

/**
 * HTML cell text: reduce wikilinks to text/path, then escape — but split on `<br>` (and literal
 * newlines) so an intended line break becomes a *real* break in Word/Docs, not a literal "<br>".
 */
function htmlCell(value: string, mode: LinkHandling): string {
  const text = transformLinks(value, dataLinkMode(mode));
  return text
    .split(/<br\s*\/?>|\r?\n/i)
    .map((segment) => escapeHtml(segment))
    .join("<br>");
}

/** Markdown table (+ optional HTML table). The default, Obsidian-friendly, format. */
export function buildRowClipboard(
  rows: readonly Row[],
  columns: readonly ResolvedColumn[],
  options: RowCopyOptions,
): ClipboardPayload {
  const headers = columns.map((c) => c.label ?? c.name);

  const lines: string[] = [];
  if (options.includeHeader) {
    lines.push(`| ${headers.map((h) => escapePipe(h)).join(" | ")} |`);
    lines.push(`| ${headers.map(() => "---").join(" | ")} |`);
  }
  for (const row of rows) {
    lines.push(`| ${columns.map((c) => markdownCell(getField(row, c.name), options.linkHandling)).join(" | ")} |`);
  }
  const plain = lines.join("\n");

  if (!options.includeHtml) return { plain };

  const thead = options.includeHeader
    ? `<thead><tr>${headers.map((h) => `<th>${escapeHtml(h)}</th>`).join("")}</tr></thead>`
    : "";
  const tbody = `<tbody>${rows
    .map((row) => `<tr>${columns.map((c) => `<td>${htmlCell(getField(row, c.name), options.linkHandling)}</td>`).join("")}</tr>`)
    .join("")}</tbody>`;
  return { plain, html: `<table>${thead}${tbody}</table>` };
}

/** Tab-separated values — pastes into spreadsheets as cells. */
function buildDelimited(rows: readonly Row[], columns: readonly ResolvedColumn[], options: RowCopyOptions, delimiter: string): string {
  const mode = dataLinkMode(options.linkHandling);
  const clean = (v: string): string => collapseBreaks(transformLinks(v, mode));
  const lines: string[] = [];
  if (options.includeHeader) lines.push(columns.map((c) => clean(c.label ?? c.name)).join(delimiter));
  for (const row of rows) lines.push(columns.map((c) => clean(getField(row, c.name))).join(delimiter));
  return lines.join("\n");
}

/** RFC-4180-style CSV with quoting only where needed. */
function buildCsv(rows: readonly Row[], columns: readonly ResolvedColumn[], options: RowCopyOptions): string {
  const mode = dataLinkMode(options.linkHandling);
  const cell = (v: string): string => {
    const t = collapseBreaks(transformLinks(v, mode));
    return /[",\r\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
  };
  const lines: string[] = [];
  if (options.includeHeader) lines.push(columns.map((c) => cell(c.label ?? c.name)).join(","));
  for (const row of rows) lines.push(columns.map((c) => cell(getField(row, c.name))).join(","));
  return lines.join("\n");
}

/** A JSON array of objects, keyed by column label. */
function buildJson(rows: readonly Row[], columns: readonly ResolvedColumn[], options: RowCopyOptions): string {
  const mode = dataLinkMode(options.linkHandling);
  const objects = rows.map((row) => {
    const object: Record<string, string> = {};
    for (const c of columns) object[c.label ?? c.name] = collapseBreaks(transformLinks(getField(row, c.name), mode));
    return object;
  });
  return JSON.stringify(objects, null, 2);
}

/** One "Field: value" block per row — reads well pasted into prose. */
function buildBullets(rows: readonly Row[], columns: readonly ResolvedColumn[], options: RowCopyOptions): ClipboardPayload {
  const md = (v: string): string => collapseBreaks(options.linkHandling === "keep" ? v : transformLinks(v, options.linkHandling));
  const plain = rows
    .map((row) => columns.map((c) => `- **${c.label ?? c.name}**: ${md(getField(row, c.name))}`).join("\n"))
    .join("\n\n");
  if (!options.includeHtml) return { plain };
  const html = rows
    .map(
      (row) =>
        `<ul>${columns.map((c) => `<li><strong>${escapeHtml(c.label ?? c.name)}:</strong> ${htmlCell(getField(row, c.name), options.linkHandling)}</li>`).join("")}</ul>`,
    )
    .join("");
  return { plain, html };
}

/** Build the clipboard payload for a chosen format. */
export function buildClipboardFor(
  format: CopyFormat,
  rows: readonly Row[],
  columns: readonly ResolvedColumn[],
  options: RowCopyOptions,
): ClipboardPayload {
  switch (format) {
    case "tsv":
      return { plain: buildDelimited(rows, columns, options, "\t") };
    case "csv":
      return { plain: buildCsv(rows, columns, options) };
    case "json":
      return { plain: buildJson(rows, columns, options) };
    case "bullets":
      return buildBullets(rows, columns, options);
    case "kvs":
      return buildKvsRows(rows, columns, options);
    case "markdown":
    default:
      return buildRowClipboard(rows, columns, options);
  }
}

// ---- Round-trippable KVS rows ----
// A hidden HTML comment carries the column types alongside the Markdown table, so pasting the rows
// back through "Paste rows as a new view" reconstructs them with the *original* types — not just
// re-inferred text. The comment is invisible in reading view and ignored by the table parser, so it
// degrades cleanly if pasted anywhere else.

const utf8ToBase64 = (s: string): string => {
  const binary = encodeURIComponent(s).replace(/%([0-9A-F]{2})/g, (_m, h: string) => String.fromCharCode(parseInt(h, 16)));
  return btoa(binary);
};
const base64ToUtf8 = (b64: string): string => {
  const binary = atob(b64);
  return decodeURIComponent(Array.from(binary, (ch) => `%${ch.charCodeAt(0).toString(16).padStart(2, "0")}`).join(""));
};

const MARKER_RE = /<!--\s*kvs-view:1\s+([A-Za-z0-9+/=]+)\s*-->/;

export interface KvsColumnMeta {
  readonly name: string;
  readonly type: string;
}

/** Build re-importable rows: the Markdown table plus a hidden type marker. */
function buildKvsRows(rows: readonly Row[], columns: readonly ResolvedColumn[], options: RowCopyOptions): ClipboardPayload {
  // A header row is required so the table re-parses on paste, regardless of the user's header setting.
  const table = buildRowClipboard(rows, columns, { ...options, includeHeader: true });
  const meta = { columns: columns.map((c) => ({ name: c.name, type: c.typeId })) };
  const marker = `<!-- kvs-view:1 ${utf8ToBase64(JSON.stringify(meta))} -->`;
  return table.html
    ? { plain: `${marker}\n${table.plain}`, html: table.html }
    : { plain: `${marker}\n${table.plain}` };
}

/** Extract the column-type metadata from copied text, if it carries a KVS marker. */
export function parseKvsMarker(text: string): readonly KvsColumnMeta[] | null {
  const match = MARKER_RE.exec(text);
  if (!match || !match[1]) return null;
  try {
    const parsed = JSON.parse(base64ToUtf8(match[1])) as { columns?: unknown };
    if (!parsed || !Array.isArray(parsed.columns)) return null;
    return parsed.columns.filter(
      (c): c is KvsColumnMeta => Boolean(c) && typeof (c as KvsColumnMeta).name === "string" && typeof (c as KvsColumnMeta).type === "string",
    );
  } catch {
    return null;
  }
}

/** Rebuild a clean Markdown table from parsed headers + rows (pipes escaped), marker excluded. */
export function rebuildMarkdownTable(headers: readonly string[], rows: readonly (readonly string[])[]): string {
  const esc = (s: string): string => s.replace(/\\/g, "\\\\").replace(/\|/g, "\\|").replace(/\r?\n+/g, " ");
  const lines = [`| ${headers.map(esc).join(" | ")} |`, `| ${headers.map(() => "---").join(" | ")} |`];
  for (const row of rows) {
    const cells = headers.map((_h, i) => esc(row[i] ?? ""));
    lines.push(`| ${cells.join(" | ")} |`);
  }
  return lines.join("\n");
}

/**
 * Slice 3 — "copy as live view": a self-contained `knowledge-view` code block that reproduces the
 * current view's query (scope, filter, grouping, sort, view type, options). Pasted into any note it
 * becomes a *live* embedded view that updates with the vault — not a frozen snapshot of rows.
 */
export function buildViewBlock(profile: Profile): string {
  const lines: string[] = [`view: ${profile.view.type}`];
  const folders = profile.scope.mode === "folders" ? profile.scope.folders.filter((f) => f.trim() !== "") : [];
  if (folders.length > 0) lines.push(`folders: ${folders.join(", ")}`);
  if (profile.advancedQuery && profile.advancedQuery.trim() !== "") lines.push(`query: ${profile.advancedQuery.trim()}`);
  if (profile.group) lines.push(`group: ${profile.group.field}`);
  if (profile.sort.length > 0) lines.push(`sort: ${profile.sort.map((s) => `${s.field} ${s.direction}`).join(", ")}`);
  if (profile.pageSize && profile.pageSize > 0) lines.push(`limit: ${profile.pageSize}`);
  for (const [key, value] of Object.entries(profile.view.options)) {
    const text = asString(value);
    if (text !== "") lines.push(`option.${key}: ${text}`);
  }
  return ["```knowledge-view", ...lines, "```"].join("\n");
}

/**
 * Write a payload to the system clipboard in every available format at once, so the paste target
 * chooses. Returns false (rather than throwing) if the clipboard is unavailable.
 */
export async function writeClipboard(payload: ClipboardPayload): Promise<boolean> {
  try {
    const clipboard = navigator.clipboard;
    if (payload.html && clipboard && "write" in clipboard && typeof ClipboardItem !== "undefined") {
      await clipboard.write([
        new ClipboardItem({
          "text/plain": new Blob([payload.plain], { type: "text/plain" }),
          "text/html": new Blob([payload.html], { type: "text/html" }),
        }),
      ]);
      return true;
    }
    if (clipboard) {
      await clipboard.writeText(payload.plain);
      return true;
    }
    return false;
  } catch {
    return false;
  }
}
