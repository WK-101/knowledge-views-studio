import { unzipSync } from "fflate";
import { findFrontmatter, parseFrontmatter, parseMarkdownTables } from "../../domain/index";
import { ANNOTATIONS_END, ANNOTATIONS_START } from "../annotations/render";
import type { IndexDoc } from "./search-index";

/**
 * Pure extraction adapters: turn each searchable source into IndexDocs. No I/O, no Obsidian — the
 * indexer supplies file content/bytes and these produce documents. Byte-based formats (Office/EPUB)
 * use fflate; PDF text lives in extract-pdf.ts because it needs the async pdf.js worker.
 *
 * Doc id scheme (also used to jump back to the source):
 *   note:<path>              row:<path>#<line>        pdf:<path>#p<page>
 *   docx:<path>  xlsx:<path>  pptx:<path>#<loc>       epub:<path>#<loc>
 */

const base = (path: string): string => path.split("/").pop()?.replace(/\.[^.]+$/, "") ?? path;

function decodeXml(s: string): string {
  return s
    .replace(/<[^>]+>/g, " ")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n: string) => String.fromCharCode(Number(n)))
    .replace(/&amp;/g, "&")
    .replace(/\s+/g, " ")
    .trim();
}

/** Concatenate the text of every <tag>…</tag> occurrence. */
function joinTag(xml: string, tag: string): string {
  return decodeXml([...xml.matchAll(new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, "g"))].map((m) => m[1] ?? "").join(" "));
}

/** Slugify a heading for a jump anchor / stable id. */
function slug(s: string): string {
  return s.trim().toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-+|-+$/g, "") || "section";
}

/** Split a note into: its KVS annotations region (the callouts), and everything else. */
function splitAnnotations(body: string): { annotations: string; rest: string } {
  const start = body.indexOf(ANNOTATIONS_START);
  const end = body.indexOf(ANNOTATIONS_END);
  if (start >= 0 && end > start) {
    return { annotations: body.slice(start + ANNOTATIONS_START.length, end), rest: body.slice(0, start) + body.slice(end + ANNOTATIONS_END.length) };
  }
  return { annotations: "", rest: body };
}

/**
 * A note becomes several documents: one per heading section (so results land on the exact section),
 * plus a separate "annotations" document for its callouts (so they can be scoped on their own and
 * aren't double-indexed with the body). Frontmatter → field-scoped facets on the intro section.
 */
export function noteToDocs(path: string, content: string): IndexDoc[] {
  const fmBlock = findFrontmatter(content);
  const fields = fmBlock ? parseFrontmatter(fmBlock.body) : {};
  const body = fmBlock ? content.split(/\r?\n/).slice(fmBlock.end + 1).join("\n") : content;
  const title = fields["title"] ?? base(path);
  const { annotations, rest } = splitAnnotations(body);
  const docs: IndexDoc[] = [];

  if (annotations.trim() !== "") {
    docs.push({ id: `annotation:${path}`, text: annotations, source: "annotation", format: "md", location: `${title} · annotations`, meta: { path, title } });
  }

  // Split the remaining body by ATX headings; content before the first heading is the intro section.
  const lines = rest.split(/\r?\n/);
  let heading = "";
  let buf: string[] = [];
  const flush = (): void => {
    const text = buf.join("\n").trim();
    if (text !== "" || heading !== "") {
      // Frontmatter fields (+ the section heading) ride on every section so field/tag search and
      // heading boosting work regardless of which section matched.
      const sectionFields = { ...fields, ...(heading !== "" ? { heading } : {}) };
      docs.push({
        id: `note:${path}#${heading === "" ? "_intro" : slug(heading)}`,
        text: (heading === "" ? "" : `${heading}\n`) + text,
        fields: sectionFields,
        source: "note",
        format: "md",
        location: heading === "" ? title : `${title} › ${heading}`,
        meta: { path, title, ...(heading !== "" ? { heading } : {}) },
      });
    }
  };
  for (const line of lines) {
    const m = /^(#{1,6})\s+(.*)$/.exec(line);
    if (m) {
      flush();
      heading = (m[2] ?? "").trim();
      buf = [];
    } else {
      buf.push(line);
    }
  }
  flush();
  if (docs.every((d) => d.source !== "note")) {
    // note had only an annotations region / was empty — still index the title so it's findable
    docs.push({ id: `note:${path}#_intro`, text: title, fields, source: "note", format: "md", location: title, meta: { path, title } });
  }
  return docs;
}

/** Each in-body table row becomes a document; columns become field-scoped facets. */
export function rowsToDocs(path: string, content: string): IndexDoc[] {
  const out: IndexDoc[] = [];
  for (const table of parseMarkdownTables(content)) {
    for (const row of table.rows) {
      const cells: Record<string, string> = {};
      table.headers.forEach((h, i) => {
        const key = h.trim();
        if (key !== "") cells[key] = row.cells[i] ?? "";
      });
      const values = Object.values(cells).filter((v) => v.trim() !== "");
      if (values.length === 0) continue;
      out.push({
        id: `row:${path}#${row.line}`,
        text: values.join(" "),
        fields: cells,
        source: "row",
        format: "md",
        location: `${base(path)} · line ${row.line + 1}`,
        meta: { path, line: row.line, title: values[0] ?? "" },
      });
    }
  }
  return out;
}

/** Wrap extracted sections (page/slide/chapter) into IndexDocs. */
export function sectionsToDocs(idPrefix: string, path: string, source: string, format: string, sections: readonly { location: string; text: string }[]): IndexDoc[] {
  return sections
    .filter((s) => s.text.trim() !== "")
    .map((s, i) => ({
      id: `${idPrefix}:${path}#${s.location || i}`,
      text: s.text,
      source,
      format,
      location: s.location === "" ? base(path) : `${base(path)} · ${s.location}`,
      meta: { path, title: base(path), ...(s.location ? { section: s.location } : {}) },
    }));
}

/** Full text of an Office file: Word → whole document; Excel → shared strings (all text cells);
 *  PowerPoint → one section per slide. */
export function extractOfficeText(bytes: ArrayBuffer, kind: "word" | "excel" | "powerpoint"): { location: string; text: string }[] {
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(new Uint8Array(bytes));
  } catch {
    return [];
  }
  const dec = new TextDecoder();
  const read = (name: string): string => (files[name] ? dec.decode(files[name]) : "");
  if (kind === "word") {
    const text = joinTag(read("word/document.xml"), "w:t");
    return text === "" ? [] : [{ location: "", text }];
  }
  if (kind === "excel") {
    const text = joinTag(read("xl/sharedStrings.xml"), "t");
    return text === "" ? [] : [{ location: "", text }];
  }
  const out: { location: string; text: string }[] = [];
  const slides = Object.keys(files)
    .map((n) => /^ppt\/slides\/slide(\d+)\.xml$/.exec(n))
    .filter((m): m is RegExpExecArray => m !== null)
    .sort((a, b) => Number(a[1]) - Number(b[1]));
  for (const m of slides) {
    const text = joinTag(dec.decode(files[m[0]]!), "a:t");
    if (text !== "") out.push({ location: `Slide ${m[1]}`, text });
  }
  return out;
}

/** Full text of an EPUB: one section per XHTML content file (scripts/styles stripped). */
export function extractEpubText(bytes: ArrayBuffer): { location: string; text: string }[] {
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(new Uint8Array(bytes));
  } catch {
    return [];
  }
  const dec = new TextDecoder();
  const out: { location: string; text: string }[] = [];
  const names = Object.keys(files)
    .filter((n) => /\.x?html?$/i.test(n) && !/nav\.xhtml$|toc\./i.test(n))
    .sort();
  let n = 0;
  for (const name of names) {
    const raw = dec.decode(files[name]!).replace(/<(script|style)\b[\s\S]*?<\/\1>/gi, " ");
    const text = decodeXml(raw);
    if (text !== "") out.push({ location: `Section ${++n}`, text });
  }
  return out;
}
