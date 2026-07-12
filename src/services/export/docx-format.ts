import { strToU8, zipSync } from "fflate";
import type { Block, CellToken } from "./cell-markdown";
import { pdfPageDimensions, resolvePdfLayout, type ExportTable, type PdfOptions } from "./export-format";

// ---- Word (.docx) export — a self-contained OOXML package built by hand ----
// Mirrors the PDF options (title/subtitle/date, page setup, accent, zebra, header
// repeat, row numbers, column widths, page numbers) and embeds images as real media.

const TWIPS_PER_MM = 1440 / 25.4;
const EMU_PER_PX = 9525;
const MARGIN_MM: Record<PdfOptions["margin"], number> = { normal: 15, narrow: 8, wide: 25 };

/** Word needs a single font name, not a CSS stack — take the first family, unquoted. */
function firstFontFamily(css: string): string {
  const first = (css || "").split(",")[0]?.trim() ?? "";
  const unquoted = first.replace(/^['"]|['"]$/g, "").trim();
  if (!unquoted || /^-apple-system$/i.test(unquoted) || /^(system-ui|ui-monospace|ui-serif|ui-sans-serif)$/i.test(unquoted)) {
    // Generic/system tokens have no Word equivalent; fall through to the next family if any.
    const next = (css || "").split(",")[1]?.trim().replace(/^['"]|['"]$/g, "") ?? "";
    return next || "Calibri";
  }
  return unquoted;
}

const esc = (v: string): string =>
  v.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const safeColor = (v: string): string => (/^#[0-9a-fA-F]{6}$/.test(v) ? v.slice(1).toUpperCase() : "4C6EF5");
/** A light tint of a hex colour (mix toward white) for header shading. */
function tint(hex6: string, ratio: number): string {
  const n = parseInt(hex6, 16);
  const mix = (c: number): number => Math.round(c + (255 - c) * ratio);
  const r = mix((n >> 16) & 0xff);
  const g = mix((n >> 8) & 0xff);
  const b = mix(n & 0xff);
  return ((r << 16) | (g << 8) | b).toString(16).padStart(6, "0").toUpperCase();
}

interface Media {
  readonly name: string;
  readonly bytes: Uint8Array;
  readonly ext: string;
  readonly rId: string;
  readonly emuW: number;
  readonly emuH: number;
}

function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Read intrinsic pixel dimensions from PNG/GIF/JPEG headers; fall back to a default. */
function imageSize(bytes: Uint8Array): { w: number; h: number } {
  // PNG
  if (bytes.length > 24 && bytes[0] === 0x89 && bytes[1] === 0x50) {
    const w = (bytes[16]! << 24) | (bytes[17]! << 16) | (bytes[18]! << 8) | bytes[19]!;
    const h = (bytes[20]! << 24) | (bytes[21]! << 16) | (bytes[22]! << 8) | bytes[23]!;
    if (w > 0 && h > 0) return { w, h };
  }
  // GIF
  if (bytes.length > 10 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) {
    return { w: bytes[6]! | (bytes[7]! << 8), h: bytes[8]! | (bytes[9]! << 8) };
  }
  // JPEG — scan SOF markers
  if (bytes.length > 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
    let i = 2;
    while (i + 9 < bytes.length) {
      if (bytes[i] !== 0xff) {
        i++;
        continue;
      }
      const marker = bytes[i + 1]!;
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        const h = (bytes[i + 5]! << 8) | bytes[i + 6]!;
        const w = (bytes[i + 7]! << 8) | bytes[i + 8]!;
        if (w > 0 && h > 0) return { w, h };
        break;
      }
      const len = (bytes[i + 2]! << 8) | bytes[i + 3]!;
      i += 2 + (len || 2);
    }
  }
  return { w: 200, h: 150 };
}

const runText = (text: string, rpr: string): string =>
  `<w:r>${rpr}<w:t xml:space="preserve">${esc(text)}</w:t></w:r>`;

function para(text: string, opts: { size: number; bold?: boolean; color?: string; font: string; after?: number; before?: number }): string {
  const rpr =
    `<w:rPr><w:rFonts w:ascii="${opts.font}" w:hAnsi="${opts.font}"/>` +
    (opts.bold ? "<w:b/>" : "") +
    (opts.color ? `<w:color w:val="${opts.color}"/>` : "") +
    `<w:sz w:val="${opts.size * 2}"/></w:rPr>`;
  const spacing = `<w:spacing w:before="${opts.before ?? 0}" w:after="${opts.after ?? 60}"/>`;
  return `<w:p><w:pPr>${spacing}</w:pPr>${runText(text, rpr)}</w:p>`;
}

/**
 * Build a .docx package from the export table + shared PDF-style options.
 * Images (resolved into `table.images` as data URLs) are embedded as media.
 */
export function buildDocx(table: ExportTable, opts: PdfOptions): Uint8Array {
  const { pageSize, orientation } = resolvePdfLayout(table, opts);
  const [pageWmm, pageHmm] = pdfPageDimensions(pageSize, orientation);
  const marginMm = MARGIN_MM[opts.margin] ?? 15;
  const font = firstFontFamily(opts.fontFamily);
  const accent = safeColor(opts.accent);
  const headerFill = tint(accent, 0.86);
  const zebraFill = "F5F6F8";
  const contentTwips = Math.max(1000, Math.round((pageWmm - marginMm * 2) * TWIPS_PER_MM));

  // ---- media (images) ----
  const media: Media[] = [];
  const byUrl = new Map<string, Media>();
  const registerImage = (dataUrl: string): Media | null => {
    const cached = byUrl.get(dataUrl);
    if (cached) return cached;
    const match = /^data:(image\/[a-z0-9.+-]+);base64,(.*)$/i.exec(dataUrl);
    if (!match) return null;
    const mime = (match[1] ?? "").toLowerCase();
    const ext = mime === "image/jpeg" ? "jpeg" : mime === "image/svg+xml" ? "svg" : mime.replace("image/", "");
    const bytes = base64ToBytes(match[2] ?? "");
    const { w, h } = imageSize(bytes);
    const scale = Math.min(1, 120 / h, 300 / w);
    const item: Media = {
      name: `image${media.length + 1}.${ext}`,
      bytes,
      ext,
      rId: `rIdImg${media.length + 1}`,
      emuW: Math.round(w * scale * EMU_PER_PX),
      emuH: Math.round(h * scale * EMU_PER_PX),
    };
    media.push(item);
    byUrl.set(dataUrl, item);
    return item;
  };

  const drawing = (m: Media): string =>
    `<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">` +
    `<wp:extent cx="${m.emuW}" cy="${m.emuH}"/><wp:effectExtent l="0" t="0" r="0" b="0"/>` +
    `<wp:docPr id="${media.indexOf(m) + 1}" name="${m.name}"/>` +
    `<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" noChangeAspect="1"/></wp:cNvGraphicFramePr>` +
    `<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">` +
    `<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">` +
    `<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">` +
    `<pic:nvPicPr><pic:cNvPr id="${media.indexOf(m) + 1}" name="${m.name}"/><pic:cNvPicPr/></pic:nvPicPr>` +
    `<pic:blipFill><a:blip r:embed="${m.rId}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>` +
    `<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${m.emuW}" cy="${m.emuH}"/></a:xfrm>` +
    `<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>`;

  // ---- columns / widths ----
  const dataCount = table.headers.length;
  const numColTwips = opts.rowNumbers ? Math.round(0.4 * 1440) : 0;
  const usableTwips = contentTwips - numColTwips;
  const widthPx = table.widths ?? [];
  const rawWidths = Array.from({ length: dataCount }, (_, i) => {
    const w = widthPx[i];
    return typeof w === "number" && w > 0 ? w : 110;
  });
  const sumW = rawWidths.reduce((a, b) => a + b, 0) || 1;
  const colTwips = rawWidths.map((w) => Math.max(400, Math.round((w / sumW) * usableTwips)));
  const gridCols = (opts.rowNumbers ? [numColTwips, ...colTwips] : colTwips)
    .map((w) => `<w:gridCol w:w="${w}"/>`)
    .join("");

  // ---- cells ----
  const rpr = (bold: boolean, color: string): string =>
    `<w:rPr><w:rFonts w:ascii="${font}" w:hAnsi="${font}"/>${bold ? "<w:b/>" : ""}<w:color w:val="${color}"/><w:sz w:val="${opts.fontSizePt * 2}"/></w:rPr>`;
  const cell = (widthTwips: number, inner: string, fill?: string): string =>
    `<w:tc><w:tcPr><w:tcW w:w="${widthTwips}" w:type="dxa"/>${fill ? `<w:shd w:val="clear" w:color="auto" w:fill="${fill}"/>` : ""}<w:tcMargins/></w:tcPr>${inner}</w:tc>`;
  const textCellBody = (text: string, bold: boolean, color: string): string =>
    `<w:p>${text.trim() === "" ? "" : runText(text, rpr(bold, color))}</w:p>`;

  // Rich-text token → run(s). Marks map to rPr; links become real external hyperlinks.
  const hyperlinks: { rId: string; url: string }[] = [];
  const markRpr = (t: CellToken): string => {
    const face = t.code ? "Consolas" : font;
    let inner = `<w:rFonts w:ascii="${face}" w:hAnsi="${face}"/>`;
    if (t.bold) inner += "<w:b/>";
    if (t.italic) inner += "<w:i/>";
    if (t.strike) inner += "<w:strike/>";
    inner += `<w:color w:val="1A1A1A"/><w:sz w:val="${opts.fontSizePt * 2}"/>`;
    return `<w:rPr>${inner}</w:rPr>`;
  };
  const linkRpr = `<w:rPr><w:rFonts w:ascii="${font}" w:hAnsi="${font}"/><w:color w:val="${accent}"/><w:u w:val="single"/><w:sz w:val="${opts.fontSizePt * 2}"/></w:rPr>`;
  const tokenRun = (t: CellToken): string => {
    if (t.kind === "break") return "<w:r><w:br/></w:r>";
    if (t.kind === "image") {
      const m = t.src ? registerImage(t.src) : null;
      return m ? drawing(m) : "";
    }
    if (t.kind === "link") {
      const rId = `rIdLink${hyperlinks.length + 1}`;
      hyperlinks.push({ rId, url: t.href ?? "" });
      return `<w:hyperlink r:id="${rId}"><w:r>${linkRpr}<w:t xml:space="preserve">${esc(t.value ?? "")}</w:t></w:r></w:hyperlink>`;
    }
    return `<w:r>${markRpr(t)}<w:t xml:space="preserve">${esc(t.value ?? "")}</w:t></w:r>`;
  };

  // Rich-text blocks → paragraphs (nested lists, quotes, code, rules, headings).
  const inlineRuns = (tokens: readonly CellToken[]): string => tokens.map(tokenRun).join("");

  // Native Word numbering: allocate list instances against two abstract definitions
  // (0 = bullets, 1 = decimal). Bullet lists share one instance; each ordered list gets a
  // fresh instance so its numbering restarts at the right value.
  const numInstances: { numId: number; abstractId: 0 | 1; start: number }[] = [];
  let nextNumId = 1;
  let bulletNumId = 0;
  const bulletNum = (): number => {
    if (bulletNumId === 0) {
      bulletNumId = nextNumId++;
      numInstances.push({ numId: bulletNumId, abstractId: 0, start: 1 });
    }
    return bulletNumId;
  };
  const orderedNum = (start: number): number => {
    const id = nextNumId++;
    numInstances.push({ numId: id, abstractId: 1, start });
    return id;
  };

  const renderList = (list: Block & { type: "list" }, level: number): string => {
    const ilvl = Math.min(level, 8);
    const numId = list.ordered ? orderedNum(list.start) : bulletNum();
    return list.items
      .map((item) => {
        let para: string;
        if (item.task !== undefined) {
          // Word has no native checkbox list; keep a literal ☐/☑ marker, indented like a list.
          const indent = 360 * (level + 1);
          const markerRun = `<w:r>${markRpr({ kind: "text" })}<w:t xml:space="preserve">${item.task ? "☑  " : "☐  "}</w:t></w:r>`;
          para = `<w:p><w:pPr><w:ind w:left="${indent}" w:hanging="220"/></w:pPr>${markerRun}${inlineRuns(item.inline)}</w:p>`;
        } else {
          para = `<w:p><w:pPr><w:numPr><w:ilvl w:val="${ilvl}"/><w:numId w:val="${numId}"/></w:numPr></w:pPr>${inlineRuns(item.inline)}</w:p>`;
        }
        const children = item.children.map((c) => renderBlock(c, level + 1)).join("");
        return para + children;
      })
      .join("");
  };
  const renderBlock = (block: Block, level: number): string => {
    switch (block.type) {
      case "p":
        return `<w:p>${inlineRuns(block.inline)}</w:p>`;
      case "heading": {
        const sz = (opts.fontSizePt + (block.level <= 2 ? 3 : block.level === 3 ? 2 : 1)) * 2;
        const runs = block.inline
          .map((t) => {
            if (t.kind === "break") return "<w:r><w:br/></w:r>";
            if (t.kind === "image") {
              const m = t.src ? registerImage(t.src) : null;
              return m ? drawing(m) : "";
            }
            return `<w:r><w:rPr><w:rFonts w:ascii="${font}" w:hAnsi="${font}"/><w:b/><w:color w:val="111111"/><w:sz w:val="${sz}"/></w:rPr><w:t xml:space="preserve">${esc(t.value ?? "")}</w:t></w:r>`;
          })
          .join("");
        return `<w:p><w:pPr><w:spacing w:before="80" w:after="40"/></w:pPr>${runs}</w:p>`;
      }
      case "hr":
        return `<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="D0D3D9"/></w:pBdr></w:pPr></w:p>`;
      case "code":
        return (
          block.text
            .split("\n")
            .map(
              (ln) =>
                `<w:p><w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F2F3F5"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="${Math.max(12, (opts.fontSizePt - 1) * 2)}"/></w:rPr><w:t xml:space="preserve">${esc(ln)}</w:t></w:r></w:p>`,
            )
            .join("") || "<w:p/>"
        );
      case "quote":
        return block.blocks
          .map((qb) => {
            if (qb.type === "p") {
              const runs = qb.inline
                .map((t) =>
                  t.kind === "break"
                    ? "<w:r><w:br/></w:r>"
                    : `<w:r><w:rPr><w:rFonts w:ascii="${font}" w:hAnsi="${font}"/><w:i/><w:color w:val="666666"/><w:sz w:val="${opts.fontSizePt * 2}"/></w:rPr><w:t xml:space="preserve">${esc(t.value ?? "")}</w:t></w:r>`,
                )
                .join("");
              return `<w:p><w:pPr><w:ind w:left="360"/><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="D0D3D9"/></w:pBdr></w:pPr>${runs}</w:p>`;
            }
            return renderBlock(qb, level);
          })
          .join("");
      case "list":
        return renderList(block, level);
    }
  };
  const renderBlocksDocx = (blocks: readonly Block[]): string => blocks.map((b) => renderBlock(b, 0)).join("") || "<w:p/>";

  // header row
  const headerCells: string[] = [];
  if (opts.rowNumbers) headerCells.push(cell(numColTwips, textCellBody("#", true, "111111"), headerFill));
  table.headers.forEach((h, i) => headerCells.push(cell(colTwips[i] ?? 400, textCellBody(h, true, "111111"), headerFill)));
  const headerRow =
    `<w:tr><w:trPr>${opts.repeatHeader ? "<w:tblHeader/>" : ""}</w:trPr>${headerCells.join("")}</w:tr>`;

  // data rows
  const bodyRows = table.rows
    .map((row, r) => {
      const fill = opts.zebra && r % 2 === 1 ? zebraFill : undefined;
      const cells: string[] = [];
      if (opts.rowNumbers) cells.push(cell(numColTwips, textCellBody(String(r + 1), false, "1A1A1A"), fill));
      row.forEach((value, c) => {
        const seg = table.segments?.[`${r}:${c}`];
        const inner = seg && seg.length > 0 ? renderBlocksDocx(seg) : textCellBody(value, false, "1A1A1A");
        cells.push(cell(colTwips[c] ?? 400, inner, fill));
      });
      return `<w:tr>${cells.join("")}</w:tr>`;
    })
    .join("");

  const border = `<w:top w:val="single" w:sz="4" w:space="0" w:color="C4C4C4"/><w:left w:val="single" w:sz="4" w:space="0" w:color="C4C4C4"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="C4C4C4"/><w:right w:val="single" w:sz="4" w:space="0" w:color="C4C4C4"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="C4C4C4"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="C4C4C4"/>`;
  const tableWidth = opts.fitToWidth ? `<w:tblW w:w="5000" w:type="pct"/>` : `<w:tblW w:w="0" w:type="auto"/>`;
  const tbl =
    `<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/>${tableWidth}<w:tblLayout w:type="fixed"/><w:tblBorders>${border}</w:tblBorders></w:tblPr>` +
    `<w:tblGrid>${gridCols}</w:tblGrid>${headerRow}${bodyRows}</w:tbl>`;

  // heading block
  const headBits: string[] = [];
  if (opts.title.trim()) headBits.push(para(opts.title, { size: opts.fontSizePt + 7, bold: true, color: "111111", font, after: 40 }));
  if (opts.subtitle.trim()) headBits.push(para(opts.subtitle, { size: opts.fontSizePt + 1, color: "555555", font, after: 40 }));
  if (opts.includeDate) headBits.push(para(new Date().toLocaleDateString(), { size: Math.max(6, opts.fontSizePt - 1), color: "888888", font, after: 120 }));

  const orientAttr = orientation === "landscape" ? ' w:orient="landscape"' : "";
  const pgW = Math.round(pageWmm * TWIPS_PER_MM);
  const pgH = Math.round(pageHmm * TWIPS_PER_MM);
  const marTwips = Math.round(marginMm * TWIPS_PER_MM);
  const footerRef = opts.pageNumbers ? `<w:footerReference w:type="default" r:id="rIdFooter"/>` : "";
  const sectPr =
    `<w:sectPr>${footerRef}<w:pgSz w:w="${pgW}" w:h="${pgH}"${orientAttr}/>` +
    `<w:pgMar w:top="${marTwips}" w:right="${marTwips}" w:bottom="${marTwips}" w:left="${marTwips}" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>`;

  const documentXml =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" ` +
    `xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" ` +
    `xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" ` +
    `xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" ` +
    `xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">` +
    `<w:body>${headBits.join("")}${tbl}${sectPr}</w:body></w:document>`;

  // ---- package parts ----
  const footerXml =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">` +
    `<w:p><w:pPr><w:jc w:val="center"/></w:pPr>` +
    `<w:r><w:rPr><w:color w:val="888888"/><w:sz w:val="${Math.max(12, (opts.fontSizePt - 1) * 2)}"/></w:rPr><w:t xml:space="preserve">Page </w:t></w:r>` +
    `<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>` +
    `<w:r><w:t xml:space="preserve"> of </w:t></w:r>` +
    `<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> NUMPAGES </w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>` +
    `</w:p></w:ftr>`;

  const usedExts = new Set(media.map((m) => m.ext));
  const imageDefaults = [...usedExts]
    .map((ext) => `<Default Extension="${ext}" ContentType="image/${ext === "jpeg" ? "jpeg" : ext === "svg" ? "svg+xml" : ext}"/>`)
    .join("");

  // Native list numbering definitions (emitted only when list paragraphs were produced).
  const hasNumbering = numInstances.length > 0;
  const BULLET_CHARS = ["•", "◦", "▪"];
  const numberingXml = hasNumbering
    ? `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
      `<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">` +
      `<w:abstractNum w:abstractNumId="0">` +
      Array.from(
        { length: 9 },
        (_, i) =>
          `<w:lvl w:ilvl="${i}"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="${BULLET_CHARS[i % 3]}"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="${(i + 1) * 360}" w:hanging="360"/></w:pPr></w:lvl>`,
      ).join("") +
      `</w:abstractNum>` +
      `<w:abstractNum w:abstractNumId="1">` +
      Array.from(
        { length: 9 },
        (_, i) =>
          `<w:lvl w:ilvl="${i}"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%${i + 1}."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="${(i + 1) * 360}" w:hanging="360"/></w:pPr></w:lvl>`,
      ).join("") +
      `</w:abstractNum>` +
      numInstances
        .map(
          (n) =>
            `<w:num w:numId="${n.numId}"><w:abstractNumId w:val="${n.abstractId}"/>` +
            (n.start !== 1 ? `<w:lvlOverride w:ilvl="0"><w:startOverride w:val="${n.start}"/></w:lvlOverride>` : "") +
            `</w:num>`,
        )
        .join("") +
      `</w:numbering>`
    : "";

  const contentTypes =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">` +
    `<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
    `<Default Extension="xml" ContentType="application/xml"/>` +
    imageDefaults +
    `<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>` +
    (opts.pageNumbers
      ? `<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>`
      : "") +
    (hasNumbering
      ? `<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>`
      : "") +
    `</Types>`;

  const rootRels =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
    `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>` +
    `</Relationships>`;

  const imageRels = media
    .map(
      (m) =>
        `<Relationship Id="${m.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${m.name}"/>`,
    )
    .join("");
  const footerRel = opts.pageNumbers
    ? `<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>`
    : "";
  const numberingRel = hasNumbering
    ? `<Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>`
    : "";
  const hyperlinkRels = hyperlinks
    .map(
      (h) =>
        `<Relationship Id="${h.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(h.url)}" TargetMode="External"/>`,
    )
    .join("");
  const docRels =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${imageRels}${footerRel}${numberingRel}${hyperlinkRels}</Relationships>`;

  const files: Record<string, Uint8Array> = {
    "[Content_Types].xml": strToU8(contentTypes),
    "_rels/.rels": strToU8(rootRels),
    "word/document.xml": strToU8(documentXml),
    "word/_rels/document.xml.rels": strToU8(docRels),
  };
  if (opts.pageNumbers) files["word/footer1.xml"] = strToU8(footerXml);
  if (hasNumbering) files["word/numbering.xml"] = strToU8(numberingXml);
  for (const m of media) files[`word/media/${m.name}`] = m.bytes;
  return zipSync(files);
}
