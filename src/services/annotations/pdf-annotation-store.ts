import { annotationId, boundingRect, hexToRgb01, rectsToQuadPoints, rgbToHex, textInRects, type AnnotationKind, type AnnotationRect, type KvsAnnotation, type PositionedText } from "../../domain/index";
// The pdf.js worker, bundled as a string by esbuild (see esbuild.config.mjs) and run from a blob URL.
import pdfWorkerSource from "./pdf.worker.txt";

// pdf.js is heavy; import the legacy build (bundler-friendly) and load lazily on first use.
type PdfjsModule = typeof import("pdfjs-dist");
let pdfjsPromise: Promise<PdfjsModule> | null = null;

export async function loadPdfjs(): Promise<PdfjsModule> {
  if (!pdfjsPromise) {
    pdfjsPromise = (async () => {
      const pdfjs = (await import("pdfjs-dist")) as unknown as PdfjsModule;
      const url = URL.createObjectURL(new Blob([pdfWorkerSource], { type: "text/javascript" }));
      pdfjs.GlobalWorkerOptions.workerSrc = url;
      return pdfjs;
    })();
  }
  return pdfjsPromise;
}

const SUBTYPE_KIND: Record<string, AnnotationKind> = {
  Highlight: "highlight",
  Underline: "underline",
  StrikeOut: "strikeout",
  Squiggly: "underline",
  Text: "note",
  FreeText: "freetext",
  Square: "square",
  Ink: "ink",
};
/** Markup kinds whose text we extract from under the quads. */
const MARKUP = new Set<AnnotationKind>(["highlight", "underline", "strikeout"]);

interface RawAnnotation {
  subtype?: string;
  quadPoints?: unknown;
  rect?: number[];
  color?: Uint8ClampedArray | number[] | null;
  contentsObj?: { str?: string };
  contents?: string;
  titleObj?: { str?: string };
  opacity?: number;
  modificationDate?: string;
}
interface RawTextItem {
  str?: string;
  transform?: number[];
  width?: number;
  height?: number;
}

function quadsToRects(quadPoints: unknown): AnnotationRect[] {
  const flat: number[] = [];
  const pushPoint = (v: unknown): void => {
    if (typeof v === "number") flat.push(v);
    else if (v && typeof (v as { x?: number }).x === "number") flat.push((v as { x: number }).x, (v as { y: number }).y);
  };
  if (Array.isArray(quadPoints)) {
    for (const q of quadPoints) {
      if (Array.isArray(q)) for (const pt of q) pushPoint(pt);
      else pushPoint(q);
    }
  } else if (quadPoints && typeof (quadPoints as ArrayLike<number>).length === "number") {
    const arr = quadPoints as ArrayLike<number>;
    for (let i = 0; i < arr.length; i++) if (typeof arr[i] === "number") flat.push(arr[i]!);
  }
  const rects: AnnotationRect[] = [];
  for (let i = 0; i + 7 < flat.length; i += 8) {
    const xs = [flat[i]!, flat[i + 2]!, flat[i + 4]!, flat[i + 6]!];
    const ys = [flat[i + 1]!, flat[i + 3]!, flat[i + 5]!, flat[i + 7]!];
    rects.push({ x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys) });
  }
  return rects;
}

function rectFrom(rect: number[] | undefined): AnnotationRect[] {
  if (!rect || rect.length < 4) return [];
  return [{ x0: Math.min(rect[0]!, rect[2]!), y0: Math.min(rect[1]!, rect[3]!), x1: Math.max(rect[0]!, rect[2]!), y1: Math.max(rect[1]!, rect[3]!) }];
}

function runsFrom(items: RawTextItem[]): PositionedText[] {
  const runs: PositionedText[] = [];
  for (const it of items) {
    if (typeof it.str !== "string" || it.str === "" || !it.transform || it.transform.length < 6) continue;
    const e = it.transform[4]!;
    const f = it.transform[5]!;
    const h = it.height || Math.hypot(it.transform[1]!, it.transform[3]!) || 10;
    const w = it.width ?? 0;
    runs.push({ str: it.str, bbox: { x0: e, y0: f, x1: e + w, y1: f + h } });
  }
  return runs;
}

function colorOf(color: Uint8ClampedArray | number[] | null | undefined): string | undefined {
  if (!color || color.length < 3) return undefined;
  return rgbToHex(color[0]!, color[1]!, color[2]!);
}

/**
 * Reads standard embedded PDF annotations into the normalised, lossless model. The canonical store IS
 * the PDF file (per the interop commitment). `read` parses; `write` injects standard annotations back
 * into the PDF — so highlights created in Obsidian are readable by Zotero and every other PDF reader.
 */
export const pdfAnnotationStore = {
  async read(bytes: ArrayBuffer, attachment: string): Promise<KvsAnnotation[]> {
    const pdfjs = await loadPdfjs();
    // pdf.js transfers the data buffer to its worker (detaching it), so hand it a private copy —
    // otherwise the caller's ArrayBuffer becomes detached and a subsequent write/remove fails.
    const data = new Uint8Array(bytes.slice(0));
    const doc = await pdfjs.getDocument({ data, isEvalSupported: false, useSystemFonts: false }).promise;
    const out: KvsAnnotation[] = [];
    try {
      for (let p = 1; p <= doc.numPages; p++) {
        const page = await doc.getPage(p);
        const annots = (await page.getAnnotations()) as RawAnnotation[];
        const markup = annots.filter((a) => a.subtype && SUBTYPE_KIND[a.subtype]);
        if (markup.length === 0) continue;
        let runs: PositionedText[] | null = null; // lazily fetch text only if a markup annotation needs it
        for (const raw of markup) {
          const kind = SUBTYPE_KIND[raw.subtype!]!;
          const rects = quadsToRects(raw.quadPoints);
          const geom = rects.length > 0 ? rects : rectFrom(raw.rect);
          let text = "";
          if (MARKUP.has(kind) && geom.length > 0) {
            if (runs === null) runs = runsFrom((await page.getTextContent()).items as RawTextItem[]);
            text = textInRects(geom, runs);
          } else if (kind === "freetext") {
            text = (raw.contentsObj?.str ?? raw.contents ?? "").trim();
          }
          const comment = kind === "freetext" ? "" : (raw.contentsObj?.str ?? raw.contents ?? "").trim();
          const base = { attachment, page: p, kind, text, rects: geom };
          out.push({
            id: annotationId(base),
            kind,
            text,
            comment,
            page: p,
            rects: geom,
            source: "pdf-embedded",
            attachment,
            ...(colorOf(raw.color) ? { color: colorOf(raw.color) } : {}),
            ...(typeof raw.opacity === "number" ? { opacity: raw.opacity } : {}),
            ...(raw.titleObj?.str ? { author: raw.titleObj.str } : {}),
          });
        }
      }
    } finally {
      await doc.destroy();
    }
    return out;
  },

  /** Inject annotations as standard embedded PDF annotations; returns the new PDF bytes. */
  async write(bytes: ArrayBuffer, annotations: readonly KvsAnnotation[]): Promise<ArrayBuffer> {
    const pdfLib = await import("pdf-lib");
    const doc = await pdfLib.PDFDocument.load(bytes);
    addAnnotationDicts(pdfLib, doc, annotations);
    const saved = await doc.save({ useObjectStreams: false });
    return toArrayBuffer(saved);
  },

  /**
   * Append annotations using an incremental PDF update: the original bytes are kept verbatim and only
   * the new annotation objects + the touched page objects are appended (fast, no full rewrite of a
   * big file). The result is validated by re-parsing; on any doubt it falls back to a full rewrite so
   * a PDF is never corrupted.
   */
  async addAnnotations(bytes: ArrayBuffer, annotations: readonly KvsAnnotation[]): Promise<ArrayBuffer> {
    const pdfLib = await import("pdf-lib");
    try {
      const incremental = await buildIncremental(pdfLib, bytes, async (doc) => addAnnotationDicts(pdfLib, doc, annotations));
      if (incremental && (await validForRead(pdfLib, incremental))) return toArrayBuffer(incremental);
    } catch (error) {
      console.warn("[KVS] incremental append failed; using full save:", error);
    }
    return this.write(bytes, annotations);
  },

  /** Remove annotations matching the given page+rect targets; incremental with full-save fallback. */
  async removeAnnotations(bytes: ArrayBuffer, targets: readonly AnnotationTarget[]): Promise<ArrayBuffer> {
    const pdfLib = await import("pdf-lib");
    const mutate = (doc: import("pdf-lib").PDFDocument): import("pdf-lib").PDFRef[] => removeMatchingAnnotations(pdfLib, doc, targets);
    try {
      const incremental = await buildIncremental(pdfLib, bytes, mutate);
      if (incremental && (await validForRead(pdfLib, incremental))) return toArrayBuffer(incremental);
    } catch (error) {
      console.warn("[KVS] incremental remove failed; using full save:", error);
    }
    const doc = await pdfLib.PDFDocument.load(bytes);
    mutate(doc);
    return toArrayBuffer(await doc.save({ useObjectStreams: false }));
  },
};

export interface AnnotationTarget {
  readonly page: number;
  readonly rect: readonly [number, number, number, number];
}

type PdfLib = typeof import("pdf-lib");
type PDFRefT = import("pdf-lib").PDFRef;
type PDFDocT = import("pdf-lib").PDFDocument;

function toArrayBuffer(u8: Uint8Array): ArrayBuffer {
  return u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength) as ArrayBuffer;
}

const enc = (s: string): Uint8Array => new TextEncoder().encode(s);
function concat(parts: readonly Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

/** Build the annotation dict for each KvsAnnotation, append to its page; returns changed refs. */
function addAnnotationDicts(pdfLib: PdfLib, doc: PDFDocT, annotations: readonly KvsAnnotation[]): PDFRefT[] {
  const { PDFName, PDFString } = pdfLib;
  const pages = doc.getPages();
  const changed: PDFRefT[] = [];
  const touched = new Set<PDFRefT>();
  for (const ann of annotations) {
    const page = pages[ann.page - 1];
    if (!page || ann.rects.length === 0) continue;
    const [r, g, b] = hexToRgb01(ann.color);
    const subtype = ann.kind === "underline" ? "Underline" : ann.kind === "strikeout" ? "StrikeOut" : ann.kind === "note" ? "Text" : "Highlight";
    const dict: Record<string, string | number | number[]> = { Type: "Annot", Subtype: subtype, Rect: boundingRect(ann.rects), C: [r, g, b], F: 4 };
    if (ann.kind !== "note") dict.QuadPoints = rectsToQuadPoints(ann.rects);
    if (typeof ann.opacity === "number") dict.CA = ann.opacity;
    const obj = doc.context.obj(dict);
    obj.set(PDFName.of("Contents"), PDFString.of(ann.comment ?? ""));
    obj.set(PDFName.of("T"), PDFString.of(ann.author ?? "Obsidian (KVS)"));
    obj.set(PDFName.of("M"), PDFString.of(`D:${new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14)}`));
    const ref = doc.context.register(obj);
    changed.push(ref);
    const annots = page.node.Annots();
    if (annots) annots.push(ref);
    else page.node.set(PDFName.of("Annots"), doc.context.obj([ref]));
    touched.add(page.ref);
  }
  for (const p of touched) changed.push(p);
  return changed;
}

/** Remove page /Annots entries whose /Rect matches a target; returns changed page refs. */
function removeMatchingAnnotations(pdfLib: PdfLib, doc: PDFDocT, targets: readonly AnnotationTarget[]): PDFRefT[] {
  const { PDFArray, PDFNumber } = pdfLib;
  const pages = doc.getPages();
  const changed: PDFRefT[] = [];
  const close = (a: readonly number[], b: readonly number[]): boolean => a.length === 4 && b.length === 4 && a.every((v, i) => Math.abs(v - b[i]!) <= 2);
  const byPage = new Map<number, AnnotationTarget[]>();
  for (const t of targets) {
    const arr = byPage.get(t.page) ?? [];
    arr.push(t);
    byPage.set(t.page, arr);
  }
  for (const [pageNum, ts] of byPage) {
    const page = pages[pageNum - 1];
    if (!page) continue;
    const annots = page.node.Annots();
    if (!(annots instanceof PDFArray)) continue;
    const removeIdx: number[] = [];
    for (let i = 0; i < annots.size(); i++) {
      const obj = annots.lookup(i, pdfLib.PDFDict);
      const rectArr = obj?.lookup(pdfLib.PDFName.of("Rect"), PDFArray);
      if (!rectArr) continue;
      const rect = [0, 1, 2, 3].map((k) => (rectArr.lookup(k, PDFNumber) as import("pdf-lib").PDFNumber | undefined)?.asNumber() ?? NaN);
      if (ts.some((t) => close(rect, t.rect))) removeIdx.push(i);
    }
    for (const i of removeIdx.reverse()) annots.remove(i);
    if (removeIdx.length > 0) changed.push(page.ref);
  }
  return changed;
}

/** Serialize an indirect object: "N G obj\n<body>\nendobj\n". */
function serializeIndirect(ref: PDFRefT, obj: import("pdf-lib").PDFObject): Uint8Array {
  const body = new Uint8Array(obj.sizeInBytes());
  obj.copyBytesInto(body, 0);
  return concat([enc(`${ref.objectNumber} ${ref.generationNumber} obj\n`), body, enc("\nendobj\n")]);
}

/** Last `startxref <n>` offset in the file, or null. */
function findStartxref(bytes: Uint8Array): number | null {
  const tail = new TextDecoder("latin1").decode(bytes.slice(Math.max(0, bytes.length - 4096)));
  const all = [...tail.matchAll(/startxref\s+(\d+)/g)];
  const last = all[all.length - 1];
  return last ? Number(last[1]) : null;
}

/** Produce an incremental-update PDF (original bytes + changed objects + xref + trailer), or null. */
async function buildIncremental(pdfLib: PdfLib, bytes: ArrayBuffer, mutate: (doc: PDFDocT) => PDFRefT[] | Promise<PDFRefT[]>): Promise<Uint8Array | null> {
  const orig = new Uint8Array(bytes);
  const prev = findStartxref(orig);
  if (prev === null) return null;
  const doc = await pdfLib.PDFDocument.load(bytes);
  const changed = await mutate(doc);
  if (changed.length === 0) return orig;
  const rootRef = doc.context.trailerInfo.Root;
  if (!(rootRef instanceof pdfLib.PDFRef)) return null;

  let maxObj = 0;
  for (const [ref] of doc.context.enumerateIndirectObjects()) maxObj = Math.max(maxObj, ref.objectNumber);

  const uniq = new Map<number, PDFRefT>();
  for (const r of changed) uniq.set(r.objectNumber, r);
  const refs = [...uniq.values()].sort((a, b) => a.objectNumber - b.objectNumber);

  const parts: Uint8Array[] = [orig];
  let offset = orig.length;
  if (orig[orig.length - 1] !== 0x0a) {
    parts.push(enc("\n"));
    offset += 1;
  }
  const offsets = new Map<number, number>();
  for (const ref of refs) {
    const obj = doc.context.lookup(ref);
    if (!obj) continue;
    const objBytes = serializeIndirect(ref, obj);
    offsets.set(ref.objectNumber, offset);
    parts.push(objBytes);
    offset += objBytes.length;
  }
  const xrefOffset = offset;
  parts.push(enc(buildXref(offsets)));
  parts.push(enc(`trailer\n<< /Size ${maxObj + 1} /Root ${rootRef.objectNumber} ${rootRef.generationNumber} R /Prev ${prev} >>\nstartxref\n${xrefOffset}\n%%EOF\n`));
  return concat(parts);
}

/** Classic xref table for the changed objects, grouped into contiguous subsections. */
function buildXref(offsets: Map<number, number>): string {
  const nums = [...offsets.keys()].sort((a, b) => a - b);
  let xref = "xref\n";
  let i = 0;
  while (i < nums.length) {
    let j = i;
    while (j + 1 < nums.length && nums[j + 1] === nums[j]! + 1) j++;
    xref += `${nums[i]} ${j - i + 1}\n`;
    for (let k = i; k <= j; k++) xref += `${String(offsets.get(nums[k]!)!).padStart(10, "0")} 00000 n \n`;
    i = j + 1;
  }
  return xref;
}

/** Re-parse guard: the incremental result must load and expose pages. */
async function validForRead(pdfLib: PdfLib, bytes: Uint8Array): Promise<boolean> {
  try {
    const doc = await pdfLib.PDFDocument.load(toArrayBuffer(bytes));
    return doc.getPageCount() > 0;
  } catch {
    return false;
  }
}
