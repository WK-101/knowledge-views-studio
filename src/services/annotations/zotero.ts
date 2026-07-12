import { annotationId, type AnnotationKind, type AnnotationRect, type KvsAnnotation } from "../../domain/index";
import type { Attachment } from "../attachments/attachment";

/** A Zotero Web/local-API item envelope (only the fields we use). */
export interface ZoteroItem {
  readonly key?: string;
  readonly data?: {
    readonly itemType?: string;
    readonly parentItem?: string;
    readonly DOI?: string;
    readonly annotationType?: string;
    readonly annotationText?: string;
    readonly annotationComment?: string;
    readonly annotationColor?: string;
    readonly annotationPageLabel?: string;
    readonly annotationPosition?: string; // JSON string
    readonly annotationAuthorName?: string;
  };
}

/** Normalise a DOI for comparison (strip URL prefixes, lowercase). */
export function normalizeDoiValue(doi: string): string {
  return doi
    .trim()
    .toLowerCase()
    .replace(/^https?:\/\/(dx\.)?doi\.org\//, "")
    .replace(/^doi:/, "")
    .trim();
}

const ZKIND: Record<string, AnnotationKind> = {
  highlight: "highlight",
  underline: "underline",
  note: "note",
  image: "image",
  ink: "ink",
  text: "note",
};

/** Extract Zotero item/attachment keys from `zotero://…/items/KEY` attachment links. */
export function zoteroKeysFromAttachments(attachments: readonly Attachment[]): string[] {
  const keys: string[] = [];
  for (const a of attachments) {
    if (a.isLink) continue;
    const m = /zotero:\/\/[^/]*\/(?:open-pdf|select)?\/?(?:library|groups\/\d+)\/items\/([A-Z0-9]+)/i.exec(a.target);
    if (m?.[1]) keys.push(m[1]);
  }
  return [...new Set(keys)];
}

function rectsFromPosition(positionJson: string | undefined): { page: number; rects: AnnotationRect[] } {
  if (!positionJson) return { page: 1, rects: [] };
  try {
    const pos = JSON.parse(positionJson) as { pageIndex?: number; rects?: number[][] };
    const page = (pos.pageIndex ?? 0) + 1;
    const rects: AnnotationRect[] = (pos.rects ?? [])
      .filter((r) => r.length >= 4)
      .map((r) => ({ x0: Math.min(r[0]!, r[2]!), y0: Math.min(r[1]!, r[3]!), x1: Math.max(r[0]!, r[2]!), y1: Math.max(r[1]!, r[3]!) }));
    return { page, rects };
  } catch {
    return { page: 1, rects: [] };
  }
}

/** Convert one Zotero annotation item to the normalised model. Returns null for non-annotation items. */
export function parseZoteroAnnotation(item: ZoteroItem, attachment: string): KvsAnnotation | null {
  const d = item.data;
  if (!d || d.itemType !== "annotation" || !d.annotationType) return null;
  const kind = ZKIND[d.annotationType.toLowerCase()] ?? "highlight";
  const { page, rects } = rectsFromPosition(d.annotationPosition);
  const text = (d.annotationText ?? "").trim();
  const comment = (d.annotationComment ?? "").trim();
  const base = { attachment, page, kind, text, rects };
  return {
    id: annotationId(base),
    kind,
    text,
    comment,
    page,
    rects,
    source: "zotero",
    attachment,
    ...(d.annotationColor ? { color: d.annotationColor } : {}),
    ...(d.annotationPageLabel ? { pageLabel: d.annotationPageLabel } : {}),
    ...(d.annotationAuthorName ? { author: d.annotationAuthorName } : {}),
  };
}

export function parseZoteroAnnotations(items: readonly ZoteroItem[], attachment: string): KvsAnnotation[] {
  const out: KvsAnnotation[] = [];
  for (const it of items) {
    const a = parseZoteroAnnotation(it, attachment);
    if (a) out.push(a);
  }
  return out;
}

/** A Zotero deep link that opens the annotation in Zotero's reader. */
export function zoteroDeepLink(attachmentKey: string, page: number, annotationKey?: string): string {
  const anno = annotationKey ? `&annotation=${annotationKey}` : "";
  return `zotero://open-pdf/library/items/${attachmentKey}?page=${page}${anno}`;
}
