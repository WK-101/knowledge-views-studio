import { unzipSync } from "fflate";
import { annotationId, type AnnotationSource, type KvsAnnotation } from "../../domain/index";
import type { AttachmentKind } from "../attachments/attachment";

/** A comment extracted from an Office document (Word/Excel/PowerPoint). */
export interface OfficeComment {
  readonly text: string;
  readonly author?: string;
  readonly date?: string;
  readonly ref?: string; // cell/slide/location context
}

function decodeXml(s: string): string {
  return s
    .replace(/<[^>]+>/g, "") // strip any nested tags (e.g. runs) inside text
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n: string) => String.fromCharCode(Number(n)))
    .replace(/&amp;/g, "&")
    .replace(/\s+/g, " ")
    .trim();
}

function attr(tag: string, name: string): string | undefined {
  return new RegExp(`${name}="([^"]*)"`).exec(tag)?.[1];
}

/** Word: `word/comments.xml` — <w:comment> with <w:t> text runs. */
export function parseDocxComments(xml: string): OfficeComment[] {
  const out: OfficeComment[] = [];
  for (const m of xml.matchAll(/<w:comment\b([^>]*)>([\s\S]*?)<\/w:comment>/g)) {
    const open = m[1] ?? "";
    const body = m[2] ?? "";
    const text = decodeXml([...body.matchAll(/<w:t\b[^>]*>([\s\S]*?)<\/w:t>/g)].map((t) => t[1] ?? "").join(" "));
    if (text !== "") out.push({ text, ...(attr(open, "w:author") ? { author: attr(open, "w:author") } : {}), ...(attr(open, "w:date") ? { date: attr(open, "w:date") } : {}) });
  }
  return out;
}

/** Excel legacy: `xl/comments*.xml` — <comment ref="A1" authorId> with <t> runs + an <authors> list. */
export function parseXlsxComments(xml: string): OfficeComment[] {
  const authors = [...xml.matchAll(/<author>([\s\S]*?)<\/author>/g)].map((a) => decodeXml(a[1] ?? ""));
  const out: OfficeComment[] = [];
  for (const m of xml.matchAll(/<comment\b([^>]*)>([\s\S]*?)<\/comment>/g)) {
    const open = m[1] ?? "";
    const body = m[2] ?? "";
    const text = decodeXml([...body.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((t) => t[1] ?? "").join(" "));
    const authorId = Number(attr(open, "authorId") ?? "-1");
    if (text !== "") out.push({ text, ...(attr(open, "ref") ? { ref: attr(open, "ref") } : {}), ...(authors[authorId] ? { author: authors[authorId] } : {}) });
  }
  return out;
}

/** Excel modern threaded comments: `xl/threadedComments/*.xml`. */
export function parseXlsxThreadedComments(xml: string): OfficeComment[] {
  const out: OfficeComment[] = [];
  for (const m of xml.matchAll(/<threadedComment\b([^>]*)>([\s\S]*?)<\/threadedComment>/g)) {
    const open = m[1] ?? "";
    const body = m[2] ?? "";
    const text = decodeXml(/<text>([\s\S]*?)<\/text>/.exec(body)?.[1] ?? "");
    if (text !== "") out.push({ text, ...(attr(open, "ref") ? { ref: attr(open, "ref") } : {}) });
  }
  return out;
}

/** PowerPoint: `ppt/comments/*.xml` (legacy <p:cm><p:text>, or modern <p:cm><p:txBody>). */
export function parsePptxComments(xml: string, authors: string[]): OfficeComment[] {
  const out: OfficeComment[] = [];
  for (const m of xml.matchAll(/<p:cm\b([^>]*)>([\s\S]*?)<\/p:cm>/g)) {
    const open = m[1] ?? "";
    const body = m[2] ?? "";
    const legacy = /<p:text>([\s\S]*?)<\/p:text>/.exec(body)?.[1];
    const text = decodeXml(legacy ?? [...body.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g)].map((t) => t[1] ?? "").join(" "));
    const authorId = Number(attr(open, "authorId") ?? "-1");
    if (text !== "") out.push({ text, ...(authors[authorId] ? { author: authors[authorId] } : {}) });
  }
  return out;
}

export function parsePptxAuthors(xml: string): string[] {
  return [...xml.matchAll(/<p:cmAuthor\b([^>]*)/g)].map((m) => attr(m[1] ?? "", "name") ?? "");
}

/** Word highlight colour names → hex (so colour→theme mapping works). */
const WORD_HIGHLIGHT_HEX: Record<string, string> = {
  yellow: "#ffff00", green: "#00ff00", cyan: "#00ffff", magenta: "#ff00ff", blue: "#0000ff", red: "#ff0000",
  darkBlue: "#000080", darkCyan: "#008080", darkGreen: "#008000", darkMagenta: "#800080", darkRed: "#800000",
  darkYellow: "#808000", darkGray: "#808080", lightGray: "#c0c0c0", black: "#000000", white: "#ffffff",
};

/** Word `document.xml` — runs carrying a <w:highlight> are highlighted text; merge consecutive runs of
 *  the same colour into one highlight. */
export function parseDocxHighlights(documentXml: string): { text: string; color: string }[] {
  const out: { text: string; color: string }[] = [];
  let current: { color: string; parts: string[] } | null = null;
  const flush = (): void => {
    if (!current) return;
    const text = decodeXml(current.parts.join(""));
    if (text !== "") out.push({ text, color: WORD_HIGHLIGHT_HEX[current.color] ?? "#ffff00" });
    current = null;
  };
  for (const m of documentXml.matchAll(/<w:r\b[^>]*>([\s\S]*?)<\/w:r>/g)) {
    const run = m[1] ?? "";
    const hl = /<w:highlight\s+w:val="([^"]+)"/.exec(run)?.[1];
    const runText = [...run.matchAll(/<w:t\b[^>]*>([\s\S]*?)<\/w:t>/g)].map((t) => t[1] ?? "").join("");
    if (hl && hl !== "none") {
      if (current && current.color === hl) current.parts.push(runText);
      else {
        flush();
        current = { color: hl, parts: [runText] };
      }
    } else {
      flush();
    }
  }
  flush();
  return out;
}

/** PowerPoint slide runs carrying <a:highlight> are highlighted text; merge same-colour runs. */
export function parsePptxHighlights(slideXml: string): { text: string; color: string }[] {
  const out: { text: string; color: string }[] = [];
  let current: { color: string; parts: string[] } | null = null;
  const flush = (): void => {
    if (!current) return;
    const text = decodeXml(current.parts.join(""));
    if (text !== "") out.push({ text, color: current.color });
    current = null;
  };
  for (const m of slideXml.matchAll(/<a:r\b[^>]*>([\s\S]*?)<\/a:r>/g)) {
    const run = m[1] ?? "";
    const hex = /<a:highlight>[\s\S]*?<a:srgbClr\s+val="([0-9A-Fa-f]{6})"/.exec(run)?.[1];
    const runText = [...run.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g)].map((t) => t[1] ?? "").join("");
    if (hex) {
      const color = `#${hex.toLowerCase()}`;
      if (current && current.color === color) current.parts.push(runText);
      else {
        flush();
        current = { color, parts: [runText] };
      }
    } else {
      flush();
    }
  }
  flush();
  return out;
}

/** Excel style fills (index → hex), from styles.xml <fills>. */
export function parseXlsxFills(stylesXml: string): (string | null)[] {
  const block = /<fills\b[^>]*>([\s\S]*?)<\/fills>/.exec(stylesXml)?.[1] ?? "";
  return [...block.matchAll(/<fill>([\s\S]*?)<\/fill>/g)].map((m) => {
    const body = m[1] ?? "";
    if (!/patternType="solid"/.test(body)) return null;
    const rgb = /<fgColor\b[^>]*\brgb="([0-9A-Fa-f]{6,8})"/.exec(body)?.[1];
    return rgb ? `#${rgb.slice(-6).toLowerCase()}` : null;
  });
}

/** Excel cell formats (xf index → fillId), from styles.xml <cellXfs>. */
export function parseXlsxCellFillIds(stylesXml: string): number[] {
  const block = /<cellXfs\b[^>]*>([\s\S]*?)<\/cellXfs>/.exec(stylesXml)?.[1] ?? "";
  return [...block.matchAll(/<xf\b([^>]*?)\/?>/g)].map((m) => Number(attr(m[1] ?? "", "fillId") ?? "0"));
}

/** Excel shared strings (index → text). */
export function parseSharedStrings(xml: string): string[] {
  return [...xml.matchAll(/<si>([\s\S]*?)<\/si>/g)].map((m) => decodeXml([...(m[1] ?? "").matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((t) => t[1] ?? "").join("")));
}

/** Cells with a user fill (fillId ≥ 2) and a value → highlighted cells. */
export function parseXlsxHighlightCells(sheetXml: string, fills: (string | null)[], cellFillIds: number[], strings: string[]): { ref: string; text: string; color: string }[] {
  const out: { ref: string; text: string; color: string }[] = [];
  for (const m of sheetXml.matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
    const open = m[1] ?? "";
    const body = m[2] ?? "";
    const s = Number(attr(open, "s") ?? "-1");
    if (s < 0) continue;
    const fillId = cellFillIds[s];
    if (fillId === undefined || fillId < 2) continue;
    const color = fills[fillId];
    if (!color || color === "#ffffff") continue;
    const t = attr(open, "t");
    let value = "";
    if (t === "s") value = strings[Number(/<v>([\s\S]*?)<\/v>/.exec(body)?.[1] ?? "-1")] ?? "";
    else if (t === "inlineStr") value = decodeXml(/<t\b[^>]*>([\s\S]*?)<\/t>/.exec(body)?.[1] ?? "");
    else value = decodeXml(/<v>([\s\S]*?)<\/v>/.exec(body)?.[1] ?? "");
    if (value.trim() !== "") out.push({ ref: attr(open, "r") ?? "", text: value, color });
  }
  return out;
}

const KIND_SOURCE: Partial<Record<AttachmentKind, AnnotationSource>> = { word: "docx", excel: "xlsx", powerpoint: "pptx" };

/** A highlight annotation with a stable, unique id (the discriminator keeps identical text distinct). */
function highlightAnn(attachment: string, source: AnnotationSource, text: string, color: string, discriminator: string, pageLabel?: string): KvsAnnotation {
  return {
    id: annotationId({ attachment, page: 1, kind: "highlight", text: `${discriminator}|${text}`, rects: [] }),
    kind: "highlight",
    text,
    comment: "",
    page: 1,
    rects: [],
    source,
    attachment,
    color,
    ...(pageLabel ? { pageLabel } : {}),
  };
}

/** Read all comments from an Office file's bytes for the given attachment kind. */
export function readOfficeComments(bytes: ArrayBuffer, kind: AttachmentKind): OfficeComment[] {
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(new Uint8Array(bytes));
  } catch {
    return [];
  }
  const dec = new TextDecoder();
  const text = (name: string): string => (files[name] ? dec.decode(files[name]) : "");
  const out: OfficeComment[] = [];
  if (kind === "word") {
    out.push(...parseDocxComments(text("word/comments.xml")));
  } else if (kind === "excel") {
    for (const name of Object.keys(files)) {
      if (/^xl\/comments\d*\.xml$/.test(name)) out.push(...parseXlsxComments(dec.decode(files[name]!)));
      else if (/^xl\/threadedComments\/threadedComment\d*\.xml$/.test(name)) out.push(...parseXlsxThreadedComments(dec.decode(files[name]!)));
    }
  } else if (kind === "powerpoint") {
    const authors = parsePptxAuthors(text("ppt/commentAuthors.xml"));
    for (const name of Object.keys(files)) {
      if (/^ppt\/comments\/.*\.xml$/.test(name)) out.push(...parsePptxComments(dec.decode(files[name]!), authors));
    }
  }
  return out;
}

/** Convert an Office comment into the normalised annotation model (index keeps identical comments distinct). */
export function officeCommentToAnnotation(c: OfficeComment, attachment: string, kind: AttachmentKind, index = 0): KvsAnnotation {
  const source = KIND_SOURCE[kind] ?? "manual";
  const id = annotationId({ attachment, page: 1, kind: "note", text: `c${index}|${c.ref ?? ""}|${c.text}`, rects: [] });
  return {
    id,
    kind: "note",
    text: "",
    comment: c.text,
    page: 1,
    rects: [],
    source,
    attachment,
    ...(c.ref ? { pageLabel: c.ref } : {}),
    ...(c.author ? { author: c.author } : {}),
    ...(c.date ? { createdAt: c.date } : {}),
  };
}

export function readOfficeAnnotations(bytes: ArrayBuffer, attachment: string, kind: AttachmentKind): KvsAnnotation[] {
  const out: KvsAnnotation[] = readOfficeComments(bytes, kind).map((c, i) => officeCommentToAnnotation(c, attachment, kind, i));
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(new Uint8Array(bytes));
  } catch {
    return out;
  }
  const dec = new TextDecoder();
  const text = (name: string): string => (files[name] ? dec.decode(files[name]) : "");
  if (kind === "word") {
    parseDocxHighlights(text("word/document.xml")).forEach((h, i) => out.push(highlightAnn(attachment, "docx", h.text, h.color, `w${i}`)));
  } else if (kind === "powerpoint") {
    const slides = Object.keys(files)
      .map((n) => /^ppt\/slides\/slide(\d+)\.xml$/.exec(n))
      .filter((m): m is RegExpExecArray => m !== null)
      .sort((a, b) => Number(a[1]) - Number(b[1]));
    for (const m of slides) {
      const slide = Number(m[1]);
      parsePptxHighlights(dec.decode(files[m[0]]!)).forEach((h, i) => out.push(highlightAnn(attachment, "pptx", h.text, h.color, `s${slide}#${i}`, `Slide ${slide}`)));
    }
  } else if (kind === "excel") {
    const styles = text("xl/styles.xml");
    const fills = parseXlsxFills(styles);
    const cellFillIds = parseXlsxCellFillIds(styles);
    const strings = parseSharedStrings(text("xl/sharedStrings.xml"));
    for (const name of Object.keys(files)) {
      if (!/^xl\/worksheets\/sheet\d+\.xml$/.test(name)) continue;
      for (const c of parseXlsxHighlightCells(dec.decode(files[name]!), fills, cellFillIds, strings)) {
        out.push(highlightAnn(attachment, "xlsx", c.text, c.color, c.ref, c.ref));
      }
    }
  }
  return out;
}
