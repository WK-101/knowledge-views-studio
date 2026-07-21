import { renderMarkdown } from "./markdown-mini";

/**
 * Copy-all / export of a page's highlights — the whole page's annotations, out at once, in a format rich
 * enough to stand on its own.
 *
 * "Rich, like the KVS view export" is the bar: the in-app export doesn't dump raw text, it produces proper
 * documents (Markdown, HTML, CSV), and so does this. In particular the Markdown export uses the *same*
 * coloured-callout form the plugin writes imported annotations in (`> [!kvs-mark-{colour}]`), so pasting an
 * export straight into a vault gives the exact KVS-styled colours, block ids and all — the export round-trips
 * into Obsidian rather than landing as flat quotes.
 *
 * Everything here is pure: highlights, notes, and page metadata in; strings out. The palette (colour name →
 * hex) is passed in for the formats that need real colour (HTML), never read from a global, so it's testable
 * and always the vault's actual palette.
 */

/** One highlight, reduced to what an export shows. */
export interface ExportHighlight {
  readonly id: string;
  readonly color: string;
  readonly style?: string;
  readonly intensity?: string;
  readonly text: string;
  readonly note?: string;
  readonly tags?: readonly string[];
  readonly createdAt: string;
}

/** One sticky note, reduced to what an export shows. */
export interface ExportStickyNote {
  readonly id: string;
  readonly color: string;
  readonly body: string;
  readonly createdAt: string;
}

/** The page the annotations came from, plus when the export was made (passed in, so this stays pure). */
export interface ExportMeta {
  readonly title: string;
  readonly url: string;
  readonly exportedAt: string;
}

export interface PageExport {
  readonly meta: ExportMeta;
  readonly highlights: readonly ExportHighlight[];
  readonly notes: readonly ExportStickyNote[];
}

/** The ISO date's day portion, or "" — deterministic and locale-independent, unlike toLocaleDateString. */
function isoDay(iso: string): string {
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return "";
  return new Date(at).toISOString().slice(0, 10);
}

/** The descriptive words for a highlight's shape and transparency, for the meta line. */
function highlightMeta(h: ExportHighlight): string {
  const bits = [h.color, h.style === "underline" ? "underline" : "", h.intensity && h.intensity !== "medium" ? h.intensity : "", isoDay(h.createdAt)];
  return bits.filter((b) => b !== undefined && b !== "").join(" · ");
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

// ------------------------------------------------------------------ Markdown

/** Prefix each line of text with a blockquote marker (blank lines kept as a bare `>`). */
function quoteLines(text: string): string {
  return text
    .split(/\r?\n/)
    .map((line) => (line.trim() === "" ? ">" : `> ${line}`))
    .join("\n");
}

/** Tags as Obsidian hashtags, spaces folded to hyphens so each is one valid tag. */
function hashtags(tags: readonly string[] | undefined): string {
  return (tags ?? []).map((t) => `#${t.replace(/\s+/g, "-").replace(/^#+/, "")}`).join(" ");
}

/**
 * The Markdown export: a document that pastes into Obsidian as native KVS callouts.
 *
 * Each highlight is a `> [!kvs-mark-{colour}]` callout with a meta line and a stable block id, its quote as
 * the callout body, its note and tags beneath — exactly what the plugin writes for an imported annotation,
 * so the colours resolve against the vault's own stylesheet. Sticky notes follow under their own heading,
 * their markdown bodies kept verbatim.
 */
export function exportMarkdown(data: PageExport): string {
  const { meta, highlights, notes } = data;
  const out: string[] = [];
  out.push(`# ${meta.title || meta.url || "Highlights"}`);
  out.push("");
  const source = meta.url !== "" ? `[Source](${meta.url})` : "";
  const when = isoDay(meta.exportedAt);
  const line = [source, when !== "" ? `Exported ${when}` : ""].filter((x) => x !== "").join(" · ");
  if (line !== "") {
    out.push(line);
    out.push("");
  }

  if (highlights.length > 0) {
    out.push(`## Highlights (${String(highlights.length)})`);
    out.push("");
    for (const h of highlights) {
      out.push(`> [!kvs-mark-${h.color}] ${highlightMeta(h)} ^anno-${h.id.slice(0, 8)}`);
      out.push(quoteLines(h.text));
      if (h.note !== undefined && h.note.trim() !== "") {
        out.push(">");
        out.push(quoteLines(`**Note:** ${h.note.trim()}`));
      }
      const tags = hashtags(h.tags);
      if (tags !== "") {
        out.push(">");
        out.push(`> ${tags}`);
      }
      out.push("");
    }
  }

  if (notes.length > 0) {
    out.push(`## Sticky notes (${String(notes.length)})`);
    out.push("");
    for (const n of notes) {
      const meta2 = [n.color, isoDay(n.createdAt)].filter((x) => x !== "").join(" · ");
      out.push(`### ${meta2}`);
      out.push(n.body.trim());
      out.push("");
    }
  }

  return out.join("\n").trimEnd() + "\n";
}

// ---------------------------------------------------------------------- HTML

/** A standalone, styled HTML document — openable, printable to PDF, shareable. */
export function exportHtml(data: PageExport, colorHex: (name: string) => string): string {
  const { meta, highlights, notes } = data;
  const title = escapeHtml(meta.title || meta.url || "Highlights");

  const chip = (tags: readonly string[] | undefined): string =>
    (tags ?? []).length === 0
      ? ""
      : `<div class="tags">${(tags ?? []).map((t) => `<span class="tag">#${escapeHtml(t)}</span>`).join("")}</div>`;

  const highlightCards = highlights
    .map((h) => {
      const hex = colorHex(h.color);
      const note = h.note !== undefined && h.note.trim() !== "" ? `<div class="note">${escapeHtml(h.note.trim())}</div>` : "";
      const metaLine = escapeHtml(highlightMeta(h));
      return `<article class="card" style="border-left-color:${hex}">
  <div class="quote"><mark style="background:${hex}33">${escapeHtml(h.text)}</mark></div>
  ${note}
  ${chip(h.tags)}
  <div class="meta">${metaLine}</div>
</article>`;
    })
    .join("\n");

  const noteCards = notes
    .map((n) => {
      const hex = colorHex(n.color);
      return `<article class="card note-card" style="border-left-color:${hex}">
  <div class="md">${renderMarkdown(n.body)}</div>
  <div class="meta">${escapeHtml([n.color, isoDay(n.createdAt)].filter((x) => x !== "").join(" · "))}</div>
</article>`;
    })
    .join("\n");

  const source = meta.url !== "" ? `<a href="${escapeHtml(meta.url)}">${escapeHtml(meta.url)}</a>` : "";
  const when = isoDay(meta.exportedAt);

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; max-width: 760px; margin: 40px auto; padding: 0 20px; color: #1a1a1a; background: #fff; }
  @media (prefers-color-scheme: dark) { body { color: #e7e7e7; background: #16161a; } .card { background: #1f1f24; } .tag { background: #2a2a31; } }
  h1 { font-size: 24px; margin: 0 0 4px; }
  h2 { font-size: 16px; margin: 28px 0 12px; color: #666; }
  .sub { color: #888; font-size: 13px; margin-bottom: 8px; }
  .sub a { color: inherit; }
  .card { background: #faf9f8; border-left: 4px solid #ccc; border-radius: 8px; padding: 12px 16px; margin: 12px 0; }
  .quote { font-size: 15.5px; }
  mark { padding: 1px 2px; border-radius: 3px; color: inherit; }
  .note { margin-top: 8px; color: #555; }
  @media (prefers-color-scheme: dark) { .note { color: #b3b3b3; } h2 { color: #9a9a9a; } }
  .tags { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
  .tag { font-size: 12px; background: #eee; border-radius: 999px; padding: 1px 9px; }
  .meta { margin-top: 8px; font-size: 12px; color: #999; text-transform: capitalize; }
  .md p { margin: 0 0 8px; } .md ul, .md ol { margin: 4px 0; padding-left: 22px; }
  .md code { background: rgba(128,128,128,0.18); padding: 1px 5px; border-radius: 5px; }
</style>
</head>
<body>
<h1>${title}</h1>
<div class="sub">${source}${source !== "" && when !== "" ? " · " : ""}${when !== "" ? `Exported ${when}` : ""}</div>
${highlights.length > 0 ? `<h2>Highlights (${String(highlights.length)})</h2>\n${highlightCards}` : ""}
${notes.length > 0 ? `<h2>Sticky notes (${String(notes.length)})</h2>\n${noteCards}` : ""}
</body>
</html>
`;
}

// ----------------------------------------------------------------------- CSV

/** Quote a CSV field when it holds a delimiter, quote, or newline (RFC 4180, doubled quotes). */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

/**
 * CSV of every annotation — highlights and sticky notes in one table, told apart by a Kind column — for a
 * spreadsheet. CRLF line endings, the way the KVS view CSV export does, for Excel.
 */
export function exportCsv(data: PageExport): string {
  const header = ["Kind", "Color", "Style", "Intensity", "Text", "Note", "Tags", "Created", "URL"];
  const rows: string[][] = [];
  for (const h of data.highlights) {
    rows.push([
      "highlight",
      h.color,
      h.style ?? "highlight",
      h.intensity ?? "medium",
      h.text,
      h.note ?? "",
      (h.tags ?? []).join(" "),
      h.createdAt,
      data.meta.url,
    ]);
  }
  for (const n of data.notes) {
    rows.push(["note", n.color, "", "", n.body, "", "", n.createdAt, data.meta.url]);
  }
  return [header, ...rows].map((r) => r.map(csvField).join(",")).join("\r\n") + "\r\n";
}

// ---------------------------------------------------------------------- JSON

/** Structured JSON — the machine copy, everything an annotation carries, for re-import or processing. */
export function exportJson(data: PageExport): string {
  return JSON.stringify(
    {
      source: { title: data.meta.title, url: data.meta.url },
      exportedAt: data.meta.exportedAt,
      highlights: data.highlights,
      notes: data.notes,
    },
    null,
    2,
  );
}

// ------------------------------------------------------------------- helpers

/** A filesystem-friendly slug from a page title, for the download filename. */
export function slugify(title: string): string {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return slug === "" ? "page" : slug;
}

export type ExportFormat = "markdown" | "html" | "csv" | "json";

/** The file extension and MIME type for a format, for a download. */
export const FORMAT_FILE: Record<ExportFormat, { ext: string; mime: string }> = {
  markdown: { ext: "md", mime: "text/markdown" },
  html: { ext: "html", mime: "text/html" },
  csv: { ext: "csv", mime: "text/csv" },
  json: { ext: "json", mime: "application/json" },
};
