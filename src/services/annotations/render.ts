import { colorName, type AnnotationSource, type KvsAnnotation } from "../../domain/index";
import { themeForColor } from "./themes";

export const ANNOTATIONS_START = "%% kvs:annotations:start %%";
export const ANNOTATIONS_END = "%% kvs:annotations:end %%";

/** Default colour → Obsidian callout type. Configurable later; drives the visual + the "meaning". */
export const DEFAULT_COLOR_CALLOUT: Readonly<Record<string, string>> = {
  yellow: "quote",
  blue: "info",
  green: "success",
  red: "warning",
  purple: "abstract",
  orange: "example",
  gray: "quote",
};

const SOURCE_LABEL: Record<AnnotationSource, string> = {
  "pdf-embedded": "PDF",
  zotero: "Zotero",
  manual: "Note",
  docx: "Word",
  xlsx: "Excel",
  pptx: "PowerPoint",
};
const DOC_SOURCES = new Set<AnnotationSource>(["docx", "xlsx", "pptx"]);

export interface RenderOptions {
  /** Deep-link target for an annotation (e.g. "paper.pdf#page=3" or a zotero:// link), or null. */
  readonly linkFor?: (a: KvsAnnotation) => string | null;
  readonly colorToCallout?: Readonly<Record<string, string>>;
  /** Colour name → research theme; when set, the theme replaces the colour name in the meta line. */
  readonly themeMap?: Readonly<Record<string, string>>;
  /** Short id prefix for block references (default "anno"). */
  readonly blockPrefix?: string;
}

function quote(text: string): string {
  return text
    .trim()
    .split("\n")
    .map((l) => `> ${l}`)
    .join("\n");
}

/** Render one annotation as an Obsidian callout with colour mapping, a block id, and a deep link. */
export function renderAnnotation(a: KvsAnnotation, options: RenderOptions = {}): string {
  const name = colorName(a.color);
  const callout = (options.colorToCallout ?? DEFAULT_COLOR_CALLOUT)[name] ?? "quote";
  const label = (options.themeMap && themeForColor(name, options.themeMap)) || name;
  const block = `${options.blockPrefix ?? "anno"}-${a.id.slice(0, 8)}`;
  const loc = a.source === "docx" ? "" : DOC_SOURCES.has(a.source) && a.pageLabel && a.pageLabel.trim() !== "" ? a.pageLabel : `p.${a.page}`;
  const meta = [loc, label, SOURCE_LABEL[a.source]].filter((x) => x !== "").join(" · ");
  const lines: string[] = [`> [!${callout}] ${meta} ^${block}`];
  if (a.text.trim() !== "") lines.push(quote(a.text));
  if (a.comment.trim() !== "") {
    if (a.text.trim() !== "") {
      lines.push(">");
      lines.push(quote(`**Note:** ${a.comment}`));
    } else {
      lines.push(quote(a.comment)); // standalone comment (Office comment, sticky note)
    }
  }
  const link = options.linkFor?.(a);
  if (link) {
    lines.push(">");
    lines.push(`> [Open ▸](${link})`);
  }
  return lines.join("\n");
}

/** Render the full managed annotations block (heading + callouts), ordered by page then position. */
export function renderAnnotationsMarkdown(annotations: readonly KvsAnnotation[], options: RenderOptions = {}): string {
  if (annotations.length === 0) return "## Annotations\n\n*No annotations found yet — highlight in your PDF (or Zotero) and sync again.*";
  const sorted = [...annotations].sort((a, b) => a.page - b.page || (b.rects[0]?.y0 ?? 0) - (a.rects[0]?.y0 ?? 0));
  return ["## Annotations", "", ...sorted.map((a) => renderAnnotation(a, options) + "\n")].join("\n").trimEnd();
}

/** Replace the managed region between markers (preserving the rest of the note), or append it. */
export function upsertAnnotationsRegion(noteText: string, renderedBlock: string): string {
  const wrapped = `${ANNOTATIONS_START}\n${renderedBlock}\n${ANNOTATIONS_END}`;
  const start = noteText.indexOf(ANNOTATIONS_START);
  const end = noteText.indexOf(ANNOTATIONS_END);
  if (start >= 0 && end > start) {
    return noteText.slice(0, start) + wrapped + noteText.slice(end + ANNOTATIONS_END.length);
  }
  const sep = noteText.endsWith("\n\n") ? "" : noteText.endsWith("\n") ? "\n" : "\n\n";
  return `${noteText}${sep}${wrapped}\n`;
}
