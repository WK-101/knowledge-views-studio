import { strToU8, zipSync } from "fflate";
import { getField, type Row } from "../../domain/index";
import { blocksHaveImage, type Block, type CellToken } from "./cell-markdown";

/** A fully-resolved rectangular table ready to serialize. */
export interface ExportTable {
  readonly headers: readonly string[];
  readonly rows: readonly (readonly string[])[];
  /** Per-column pixel widths (from the view), used to size PDF columns; undefined = auto. */
  readonly widths?: readonly (number | undefined)[];
  /**
   * Cells with rendered Markdown, keyed by "rowIndex:dataColumnIndex". Each is an ordered
   * list of blocks (paragraphs, headings, nested lists, quotes, code, rules) so rich-text
   * and image cells render faithfully instead of dumping raw Markdown.
   */
  readonly segments?: Readonly<Record<string, readonly Block[]>>;
}

export interface ExportColumn {
  readonly name: string;
  readonly label: string;
  readonly width?: number;
  readonly typeId?: string;
}

/** Build a rectangular table from rows + chosen columns, optionally appending source metadata. */
export function buildExportTable(
  rows: readonly Row[],
  columns: readonly ExportColumn[],
  includeMetadata: boolean,
): ExportTable {
  const metaHeaders = includeMetadata ? ["Source", "Table", "Row"] : [];
  const headers = [...columns.map((c) => c.label), ...metaHeaders];
  const widths = [...columns.map((c) => c.width), ...metaHeaders.map(() => undefined)];
  const dataRows = rows.map((row) => {
    const base = columns.map((c) => getField(row, c.name));
    if (!includeMetadata) return base;
    const loc = row.provenance.locator;
    return [...base, row.provenance.filePath, String(loc.tableIndex ?? ""), String(loc.rowIndex ?? "")];
  });
  return { headers, rows: dataRows, widths };
}

// ---- CSV (RFC 4180, CRLF line endings for Excel compatibility) ----
export interface CsvOptions {
  readonly delimiter: "," | ";" | "\t" | "|";
  readonly quoteAll: boolean;
  readonly newline: "crlf" | "lf";
  readonly bom: boolean;
  readonly includeHeader: boolean;
}
export const DEFAULT_CSV_OPTIONS: CsvOptions = {
  delimiter: ",",
  quoteAll: false,
  newline: "crlf",
  bom: false,
  includeHeader: true,
};

export function buildCsv(table: ExportTable, opts: CsvOptions = DEFAULT_CSV_OPTIONS): string {
  const d = opts.delimiter;
  const esc = (v: string): string =>
    opts.quoteAll || v.includes(d) || /["\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
  const line = (cells: readonly string[]): string => cells.map(esc).join(d);
  const nl = opts.newline === "lf" ? "\n" : "\r\n";
  const rows = opts.includeHeader ? [table.headers, ...table.rows] : table.rows;
  const body = rows.map(line).join(nl);
  return opts.bom ? `\uFEFF${body}` : body;
}

// ---- Markdown pipe table ----
export interface MarkdownExportOptions {
  readonly align: "none" | "left" | "center" | "right";
  readonly includeTitle: boolean;
  readonly title: string;
}
export const DEFAULT_MD_OPTIONS: MarkdownExportOptions = { align: "none", includeTitle: false, title: "" };

export function buildMarkdownTable(table: ExportTable, opts: MarkdownExportOptions = DEFAULT_MD_OPTIONS): string {
  const clean = (v: string): string => v.replace(/\|/g, "\\|").replace(/\r?\n/g, " ");
  const divCell =
    opts.align === "left" ? ":---" : opts.align === "center" ? ":---:" : opts.align === "right" ? "---:" : "---";
  const header = `| ${table.headers.map(clean).join(" | ")} |`;
  const divider = `| ${table.headers.map(() => divCell).join(" | ")} |`;
  const body = table.rows.map((r) => `| ${r.map(clean).join(" | ")} |`);
  const lines = [header, divider, ...body];
  if (opts.includeTitle && opts.title.trim() !== "") return [`# ${opts.title.trim()}`, "", ...lines].join("\n");
  return lines.join("\n");
}

// ---- XLSX (minimal OOXML, no external spreadsheet library beyond a zip) ----
function colLetter(index: number): string {
  let n = index;
  let s = "";
  do {
    s = String.fromCharCode(65 + (n % 26)) + s;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return s;
}

function xmlEscape(v: string): string {
  return v.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

const NUMERIC = /^-?(?:\d+\.?\d*|\.\d+)$/;

function cellXml(ref: string, value: string, style: number): string {
  const s = style > 0 ? ` s="${style}"` : "";
  if (value === "") return `<c r="${ref}"${s}/>`;
  if (NUMERIC.test(value)) return `<c r="${ref}"${s}><v>${value}</v></c>`;
  return `<c r="${ref}"${s} t="inlineStr"><is><t xml:space="preserve">${xmlEscape(value)}</t></is></c>`;
}

function rowXml(cells: readonly string[], rowNumber: number, style: number): string {
  const cs = cells.map((v, ci) => cellXml(`${colLetter(ci)}${rowNumber}`, v, style)).join("");
  return `<row r="${rowNumber}">${cs}</row>`;
}

export interface XlsxOptions {
  readonly sheetName: string;
  readonly boldHeader: boolean;
  readonly freezeHeader: boolean;
  readonly autoFilter: boolean;
  readonly zebra: boolean;
}
export const DEFAULT_XLSX_OPTIONS: XlsxOptions = {
  sheetName: "Export",
  boldHeader: true,
  freezeHeader: true,
  autoFilter: true,
  zebra: false,
};

// Style indices in styles.xml below: 0 default, 1 bold header, 2 zebra fill.
const STYLES_XML =
  `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
  `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
  `<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>` +
  `<fills count="3">` +
  `<fill><patternFill patternType="none"/></fill>` +
  `<fill><patternFill patternType="gray125"/></fill>` +
  `<fill><patternFill patternType="solid"><fgColor rgb="FFF2F4F8"/><bgColor indexed="64"/></patternFill></fill>` +
  `</fills>` +
  `<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>` +
  `<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>` +
  `<cellXfs count="3">` +
  `<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>` +
  `<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>` +
  `<xf numFmtId="0" fontId="0" fillId="2" borderId="0" xfId="0" applyFill="1"/>` +
  `</cellXfs>` +
  `<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>` +
  `</styleSheet>`;

export function buildXlsx(table: ExportTable, opts: XlsxOptions = DEFAULT_XLSX_OPTIONS): Uint8Array {
  const headerStyle = opts.boldHeader ? 1 : 0;
  const rowsXml = [
    rowXml(table.headers, 1, headerStyle),
    ...table.rows.map((cells, i) => rowXml(cells, i + 2, opts.zebra && i % 2 === 1 ? 2 : 0)),
  ].join("");

  const lastCol = colLetter(Math.max(0, table.headers.length - 1));
  const lastRow = table.rows.length + 1;
  const sheetViews = opts.freezeHeader
    ? `<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft"/></sheetView></sheetViews>`
    : `<sheetViews><sheetView workbookViewId="0"/></sheetViews>`;
  const autoFilter = opts.autoFilter && table.headers.length > 0 ? `<autoFilter ref="A1:${lastCol}${lastRow}"/>` : "";

  const sheet =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
    sheetViews +
    `<sheetData>${rowsXml}</sheetData>${autoFilter}</worksheet>`;
  const contentTypes =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">` +
    `<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
    `<Default Extension="xml" ContentType="application/xml"/>` +
    `<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>` +
    `<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>` +
    `<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>` +
    `</Types>`;
  const rels =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
    `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>` +
    `</Relationships>`;
  const sheetName = (opts.sheetName.trim() || "Export").replace(/[\\/?*[\]:]/g, " ").slice(0, 31);
  const workbook =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
    `<sheets><sheet name="${xmlEscape(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>`;
  const workbookRels =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
    `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>` +
    `<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>` +
    `</Relationships>`;
  return zipSync({
    "[Content_Types].xml": strToU8(contentTypes),
    "_rels/.rels": strToU8(rels),
    "xl/workbook.xml": strToU8(workbook),
    "xl/_rels/workbook.xml.rels": strToU8(workbookRels),
    "xl/styles.xml": strToU8(STYLES_XML),
    "xl/worksheets/sheet1.xml": strToU8(sheet),
  });
}

// ---- Print-ready HTML (for the browser/OS "Save as PDF") ----
export type PdfPageSize =
  | "A3"
  | "A4"
  | "A5"
  | "A6"
  | "B4"
  | "B5"
  | "Letter"
  | "Legal"
  | "Tabloid"
  | "Executive";

export interface PdfOptions {
  readonly orientation: "portrait" | "landscape" | "auto";
  readonly pageSize: PdfPageSize | "auto";
  readonly margin: "normal" | "narrow" | "wide";
  readonly fontSizePt: number;
  /** A CSS font-family value: a preset stack, or a specific locally-installed family name. */
  readonly fontFamily: string;
  readonly title: string;
  readonly subtitle: string;
  readonly accent: string;
  readonly zebra: boolean;
  readonly includeDate: boolean;
  readonly pageNumbers: boolean;
  readonly repeatHeader: boolean;
  readonly fitToWidth: boolean;
  readonly rowNumbers: boolean;
}

const MARGIN_MM: Record<PdfOptions["margin"], number> = { normal: 15, narrow: 8, wide: 25 };
const PAGE_MM: Record<PdfPageSize, readonly [number, number]> = {
  A3: [297, 420],
  A4: [210, 297],
  A5: [148, 210],
  A6: [105, 148],
  B4: [250, 353],
  B5: [176, 250],
  Letter: [216, 279],
  Legal: [216, 356],
  Tabloid: [279, 432],
  Executive: [184, 267],
};
/** Ordered pool the `auto` page picker draws from (smallest useful first). */
const AUTO_SIZES: readonly PdfPageSize[] = ["A4", "Letter", "B4", "Legal", "Tabloid", "A3"];
/** Built-in font choices offered in the export dialog (in addition to installed fonts). */
export const FONT_PRESETS: readonly { readonly label: string; readonly value: string }[] = [
  { label: "Sans-serif", value: '-apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif' },
  { label: "Serif", value: 'Georgia, "Times New Roman", Times, serif' },
  { label: "Monospace", value: '"SF Mono", "Cascadia Code", "Roboto Mono", Consolas, monospace' },
];
export const DEFAULT_EXPORT_FONT = FONT_PRESETS[0]!.value;
const PX_PER_MM = 96 / 25.4;

/** Page dimensions in mm, accounting for orientation. */
export function pdfPageDimensions(pageSize: PdfPageSize, orientation: "portrait" | "landscape"): readonly [number, number] {
  const [w, h] = PAGE_MM[pageSize];
  return orientation === "landscape" ? [h, w] : [w, h];
}

/** Estimate the printed width of the table in mm, honouring per-column widths when known. */
function estimateTableWidthMm(table: ExportTable, opts: PdfOptions): number {
  const cols = table.headers.length;
  const sample = table.rows.slice(0, 60);
  const charMm = opts.fontSizePt * 0.35278 * 0.55; // pt -> mm, times an average glyph-width factor
  const paddingMm = 4.2; // ~6pt horizontal padding per side
  let total = opts.rowNumbers ? 10 : 0;
  for (let i = 0; i < cols; i++) {
    const w = table.widths?.[i];
    if (typeof w === "number" && w > 0) {
      total += w / PX_PER_MM + paddingMm;
      continue;
    }
    let chars = table.headers[i]?.length ?? 6;
    for (const row of sample) chars = Math.max(chars, (row[i] ?? "").length);
    chars = Math.min(chars, 40);
    total += Math.max(18, chars * charMm) + paddingMm;
  }
  return total;
}

/**
 * Resolve `auto` page size / orientation. Estimates the table's printed width
 * (from real column widths where available, else content), then picks the
 * smallest candidate page whose printable width fits — preferring portrait, and
 * only escalating size/orientation as the table gets wider. Explicit choices win.
 */
export function resolvePdfLayout(
  table: ExportTable,
  opts: PdfOptions,
): { pageSize: PdfPageSize; orientation: "portrait" | "landscape" } {
  const wantMm = estimateTableWidthMm(table, opts);
  const marginMm = MARGIN_MM[opts.margin] ?? 15;
  const sizes: readonly PdfPageSize[] = opts.pageSize !== "auto" ? [opts.pageSize] : AUTO_SIZES;
  const orientations: readonly ("portrait" | "landscape")[] =
    opts.orientation !== "auto" ? [opts.orientation] : ["portrait", "landscape"];

  const candidates: { pageSize: PdfPageSize; orientation: "portrait" | "landscape"; contentW: number }[] = [];
  for (const pageSize of sizes) {
    for (const orientation of orientations) {
      const [w] = pdfPageDimensions(pageSize, orientation);
      candidates.push({ pageSize, orientation, contentW: w - marginMm * 2 });
    }
  }
  // Smallest printable width first, so we pick the least page that still fits.
  candidates.sort((a, b) => a.contentW - b.contentW);
  const fit = candidates.find((c) => c.contentW >= wantMm) ?? candidates[candidates.length - 1];
  const chosen = fit ?? { pageSize: "A4" as PdfPageSize, orientation: "portrait" as const };
  return { pageSize: chosen.pageSize, orientation: chosen.orientation };
}

const escapeHtml = (v: string): string => v.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const escapeAttr = (v: string): string => v.replace(/&/g, "&amp;").replace(/"/g, "&quot;");
const safeAccent = (v: string): string => (/^#[0-9a-fA-F]{6}$/.test(v) ? v : "#4c6ef5");

/** Wrap escaped text in emphasis/code/strike markup per the token's marks. */
function wrapMarks(html: string, t: CellToken): string {
  let out = html;
  if (t.code) out = `<code>${out}</code>`;
  if (t.strike) out = `<s>${out}</s>`;
  if (t.italic) out = `<em>${out}</em>`;
  if (t.bold) out = `<strong>${out}</strong>`;
  return out;
}

/** Render a single cell token to inline HTML. */
function renderToken(t: CellToken): string {
  if (t.kind === "break") return "<br />";
  if (t.kind === "image") return `<img src="${escapeAttr(t.src ?? "")}" alt="" />`;
  if (t.kind === "link") {
    return `<a href="${escapeAttr(t.href ?? "")}">${wrapMarks(escapeHtml(t.value ?? ""), t)}</a>`;
  }
  return wrapMarks(escapeHtml(t.value ?? ""), t);
}

const renderInline = (tokens: readonly CellToken[]): string => tokens.map(renderToken).join("");

/** Render a list of blocks to HTML (proper nested lists, quotes, code, rules). */
function renderBlocksHtml(blocks: readonly Block[]): string {
  return blocks.map(renderBlockHtml).join("");
}

function renderBlockHtml(block: Block): string {
  switch (block.type) {
    case "p":
      return `<p class="kvs-md-p">${renderInline(block.inline)}</p>`;
    case "heading":
      return `<div class="kvs-md-h kvs-md-h${block.level}">${renderInline(block.inline)}</div>`;
    case "hr":
      return `<hr class="kvs-md-hr" />`;
    case "code":
      return `<pre class="kvs-md-pre"><code>${escapeHtml(block.text)}</code></pre>`;
    case "quote":
      return `<blockquote class="kvs-md-quote">${renderBlocksHtml(block.blocks)}</blockquote>`;
    case "list": {
      const tag = block.ordered ? "ol" : "ul";
      const start = block.ordered && block.start !== 1 ? ` start="${block.start}"` : "";
      const items = block.items
        .map((item) => {
          const task = item.task === undefined ? "" : `<span class="kvs-md-check">${item.task ? "☑" : "☐"}</span> `;
          const cls = item.task === undefined ? "" : ' class="kvs-md-task"';
          return `<li${cls}>${task}${renderInline(item.inline)}${renderBlocksHtml(item.children)}</li>`;
        })
        .join("");
      return `<${tag}${start} class="kvs-md-list">${items}</${tag}>`;
    }
  }
}

/** Proportional <colgroup> from the view's column widths, or "" when none are set. */
function buildColgroup(table: ExportTable, opts: PdfOptions): string {
  const widths = table.widths;
  if (!widths || !widths.some((w) => typeof w === "number" && w > 0)) return "";
  const arr: number[] = [];
  if (opts.rowNumbers) arr.push(30);
  for (let i = 0; i < table.headers.length; i++) {
    const w = widths[i];
    arr.push(typeof w === "number" && w > 0 ? w : 110);
  }
  const sum = arr.reduce((a, b) => a + b, 0) || 1;
  return `<colgroup>${arr.map((w) => `<col style="width:${((w / sum) * 100).toFixed(3)}%">`).join("")}</colgroup>`;
}

/** Client-side pagination that numbers pages — the only reliable way in Chromium print. */
function paginationScript(pageSize: PdfPageSize, orientation: "portrait" | "landscape", opts: PdfOptions): string {
  const [wMm, hMm] = pdfPageDimensions(pageSize, orientation);
  const marginMm = MARGIN_MM[opts.margin] ?? 15;
  const nums = opts.pageNumbers ? "1" : "0";
  // Plain concatenation (no template literals) to keep this self-contained and escaping-safe.
  return (
    "<script>(function(){try{" +
    "var PXMM=" + PX_PER_MM + ";" +
    "var contentW=(" + wMm + "-" + marginMm + "*2)*PXMM;" +
    "var contentH=(" + hMm + "-" + marginMm + "*2)*PXMM;" +
    "var withNums=" + nums + ";" +
    "var footerH=withNums?22:0;var usableH=contentH-footerH;" +
    "var src=document.getElementById('kvs-doc');if(!src)return done();" +
    "var header=src.querySelector('header');var table=src.querySelector('table');if(!table)return done();" +
    "var thead=table.querySelector('thead');var colg=table.querySelector('colgroup');" +
    "var rows=Array.prototype.slice.call(table.querySelectorAll('tbody>tr'));" +
    "src.style.position='absolute';src.style.left='0';src.style.top='0';src.style.width=contentW+'px';" +
    "var headerH=header?header.getBoundingClientRect().height:0;" +
    "var theadH=thead?thead.getBoundingClientRect().height:0;" +
    "var pages=document.createElement('div');pages.id='kvs-pages';" +
    "function newPage(withHeader){var pg=document.createElement('section');pg.className='kvs-page';" +
    "pg.style.height=contentH+'px';var inner=document.createElement('div');inner.className='kvs-page-in';" +
    "if(withHeader&&header)inner.appendChild(header.cloneNode(true));" +
    "var t=document.createElement('table');if(colg)t.appendChild(colg.cloneNode(true));" +
    "if(thead)t.appendChild(thead.cloneNode(true));var tb=document.createElement('tbody');t.appendChild(tb);" +
    "inner.appendChild(t);pg.appendChild(inner);" +
    "if(withNums){var f=document.createElement('div');f.className='kvs-page-foot';pg.appendChild(f);}" +
    "pages.appendChild(pg);return{tb:tb,used:(withHeader?headerH:0)+theadH};}" +
    "var cur=newPage(true);" +
    "for(var i=0;i<rows.length;i++){var r=rows[i];var h=r.getBoundingClientRect().height||20;" +
    "if(cur.used+h>usableH&&cur.tb.childNodes.length>0){cur=newPage(false);}" +
    "cur.tb.appendChild(r);cur.used+=h;}" +
    "var pgs=pages.querySelectorAll('.kvs-page');" +
    "for(var p=0;p<pgs.length;p++){var ft=pgs[p].querySelector('.kvs-page-foot');" +
    "if(ft)ft.textContent='Page '+(p+1)+' of '+pgs.length;}" +
    "src.parentNode.removeChild(src);document.body.appendChild(pages);done();" +
    "}catch(e){done();}function done(){document.body.setAttribute('data-ready','1');}})();</script>"
  );
}

/**
 * Build the print/preview document. `mode: "print"` adds the pagination pass
 * (needed for page numbers and precise page breaks); `mode: "preview"` renders a
 * continuous, styled approximation without scripts.
 */
export function buildPrintHtml(table: ExportTable, opts: PdfOptions, mode: "print" | "preview" = "print"): string {
  const { pageSize, orientation } = resolvePdfLayout(table, opts);
  const marginMm = MARGIN_MM[opts.margin] ?? 15;
  const font = opts.fontFamily?.trim() || DEFAULT_EXPORT_FONT;
  const accent = safeAccent(opts.accent);
  const hasWidths = !!table.widths && table.widths.some((w) => typeof w === "number" && w > 0);
  const paginate = mode === "print" && (opts.pageNumbers || opts.repeatHeader);

  const headerCells = opts.rowNumbers ? ["#", ...table.headers] : table.headers;
  const thead = `<thead><tr>${headerCells.map((h) => `<th>${escapeHtml(h)}</th>`).join("")}</tr></thead>`;
  const tbody = `<tbody>${table.rows
    .map((row, index) => {
      const numberCell = opts.rowNumbers ? `<td>${index + 1}</td>` : "";
      const dataCells = row
        .map((cell, col) => {
          const seg = table.segments?.[`${index}:${col}`];
          if (seg && seg.length > 0) {
            const cls = blocksHaveImage(seg) ? ' class="kvs-img-cell"' : "";
            return `<td${cls}>${renderBlocksHtml(seg)}</td>`;
          }
          return `<td>${escapeHtml(cell)}</td>`;
        })
        .join("");
      return `<tr>${numberCell}${dataCells}</tr>`;
    })
    .join("")}</tbody>`;

  const bits: string[] = [];
  if (opts.title.trim()) bits.push(`<h1>${escapeHtml(opts.title)}</h1>`);
  if (opts.subtitle.trim()) bits.push(`<p class="sub">${escapeHtml(opts.subtitle)}</p>`);
  if (opts.includeDate) bits.push(`<p class="date">${escapeHtml(new Date().toLocaleDateString())}</p>`);
  const header = bits.length ? `<header>${bits.join("")}</header>` : "";
  const colgroup = buildColgroup(table, opts);
  const tableSizing = opts.fitToWidth || hasWidths ? "width:100%;table-layout:fixed;" : "width:auto;";
  const tableHtml = `<table>${colgroup}${thead}${tbody}</table>`;
  // In paginate mode the script consumes #kvs-doc and rebuilds numbered pages; a
  // static footer sample keeps the preview honest without running the script.
  const previewFoot = !paginate && opts.pageNumbers ? `<div class="kvs-page-foot kvs-foot-static">Page 1</div>` : "";
  const body = paginate
    ? `<div id="kvs-doc">${header}${tableHtml}</div>`
    : `${header}${tableHtml}${previewFoot}`;

  return (
    `<!DOCTYPE html><html><head><meta charset="utf-8"><title>${escapeHtml(opts.title || "Export")}</title><style>` +
    `@page { size: ${pageSize} ${orientation}; margin: ${marginMm}mm; }` +
    `* { box-sizing: border-box; }` +
    `body { font-family: ${font}; font-size: ${opts.fontSizePt}pt; color: #1a1a1a; margin: 0; -webkit-print-color-adjust: exact; print-color-adjust: exact; }` +
    `header { margin: 0 0 12pt; border-bottom: 2pt solid ${accent}; padding-bottom: 6pt; }` +
    `h1 { font-size: ${opts.fontSizePt + 7}pt; margin: 0; color: #111; }` +
    `.sub { margin: 3pt 0 0; color: #555; font-size: ${opts.fontSizePt + 1}pt; }` +
    `.date { margin: 3pt 0 0; color: #888; font-size: ${Math.max(6, opts.fontSizePt - 1)}pt; }` +
    `table { border-collapse: collapse; ${tableSizing} }` +
    `th, td { border: 0.5pt solid #c4c4c4; padding: 3pt 6pt; text-align: left; vertical-align: top; word-break: break-word; }` +
    `thead { display: ${opts.repeatHeader && !paginate ? "table-header-group" : "table-row-group"}; }` +
    `thead th { background: ${accent}1f; color: #111; font-weight: 600; border-bottom: 1pt solid ${accent}; }` +
    (opts.zebra ? `tbody tr:nth-child(even) { background: #f5f6f8; }` : ``) +
    `tr { page-break-inside: avoid; }` +
    `.kvs-img-cell img { max-height: 110px; max-width: 100%; height: auto; width: auto; object-fit: contain; vertical-align: middle; margin: 1pt 3pt 1pt 0; }` +
    `td code { font-family: ui-monospace, "SFMono-Regular", Consolas, monospace; font-size: 0.9em; background: #f2f3f5; padding: 0 3px; border-radius: 3px; }` +
    `td a { color: #3a5bd9; text-decoration: underline; }` +
    `.kvs-md-p { margin: 0 0 4pt; }` +
    `td > .kvs-md-p:last-child, li > .kvs-md-p:last-child { margin-bottom: 0; }` +
    `.kvs-md-h { font-weight: 700; margin: 5pt 0 2pt; line-height: 1.2; }` +
    `.kvs-md-h:first-child { margin-top: 0; }` +
    `.kvs-md-h1 { font-size: 1.35em; } .kvs-md-h2 { font-size: 1.22em; } .kvs-md-h3 { font-size: 1.12em; } .kvs-md-h4, .kvs-md-h5, .kvs-md-h6 { font-size: 1.04em; }` +
    `ul.kvs-md-list, ol.kvs-md-list { margin: 2pt 0; padding-left: 18pt; }` +
    `.kvs-md-list ul.kvs-md-list, .kvs-md-list ol.kvs-md-list { margin: 1pt 0; }` +
    `.kvs-md-list li { margin: 1pt 0; }` +
    `li.kvs-md-task { list-style: none; margin-left: -14pt; }` +
    `.kvs-md-check { font-family: sans-serif; }` +
    `.kvs-md-quote { border-left: 2.5pt solid #d0d3d9; margin: 3pt 0; padding: 1pt 0 1pt 8pt; color: #555; }` +
    `.kvs-md-hr { border: none; border-top: 1pt solid #d0d3d9; margin: 5pt 0; }` +
    `.kvs-md-pre { background: #f2f3f5; padding: 4pt 6pt; border-radius: 3px; white-space: pre-wrap; margin: 3pt 0; font-family: ui-monospace, "SFMono-Regular", Consolas, monospace; font-size: 0.88em; }` +
    `.kvs-page { position: relative; overflow: hidden; page-break-after: always; }` +
    `.kvs-page:last-child { page-break-after: auto; }` +
    `.kvs-page-foot { position: absolute; left: 0; right: 0; bottom: 2pt; text-align: center; color: #888; font-size: ${Math.max(6, opts.fontSizePt - 1)}pt; }` +
    `.kvs-foot-static { position: static; margin-top: 8pt; }` +
    `</style></head><body>${body}${paginate ? paginationScript(pageSize, orientation, opts) : ""}</body></html>`
  );
}
