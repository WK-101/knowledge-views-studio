/**
 * Attachments for a promoted paper note. Stored as plain, human-editable lines inside a `kvs-paper`
 * code block (portable: the note keeps working without the plugin). Each line is a vault link
 * `[[path]]` or a URL, with an optional `| label`. Parsing/serialising/typing are pure + tested.
 */
export type AttachmentKind = "pdf" | "epub" | "image" | "word" | "excel" | "powerpoint" | "web" | "file";

export interface Attachment {
  /** Wikilink target (vault path) or URL. */
  readonly target: string;
  /** true = a vault file (`[[target]]`); false = an external URL. */
  readonly isLink: boolean;
  /** Optional display label. */
  readonly label?: string;
  readonly kind: AttachmentKind;
}

const EXT_KIND: Record<string, AttachmentKind> = {
  pdf: "pdf",
  epub: "epub",
  png: "image", jpg: "image", jpeg: "image", gif: "image", webp: "image", svg: "image", bmp: "image", avif: "image",
  doc: "word", docx: "word", rtf: "word", odt: "word",
  xls: "excel", xlsx: "excel", csv: "excel", tsv: "excel", ods: "excel",
  ppt: "powerpoint", pptx: "powerpoint", odp: "powerpoint",
};

/** Classify an attachment by extension; URLs with no known extension are "web", files are "file". */
export function attachmentKind(target: string, isUrl: boolean): AttachmentKind {
  const clean = target.split(/[?#]/)[0] ?? target;
  const dot = clean.lastIndexOf(".");
  const ext = dot >= 0 ? clean.slice(dot + 1).toLowerCase() : "";
  return EXT_KIND[ext] ?? (isUrl ? "web" : "file");
}

function isUrl(s: string): boolean {
  return /^https?:\/\//i.test(s) || /^zotero:\/\//i.test(s);
}

/** Parse the lines of a `kvs-paper` block into attachments (ignoring blanks and stray text). */
export function parseAttachments(source: string): Attachment[] {
  const out: Attachment[] = [];
  for (const rawLine of source.split("\n")) {
    const line = rawLine.trim();
    if (line === "") continue;
    if (line.startsWith("[[")) {
      // Handle the wikilink first so a `|alias` inside it isn't mistaken for the label separator.
      const close = line.indexOf("]]");
      if (close < 0) continue;
      const inner = line.slice(2, close).trim();
      const target = (inner.split("|")[0] ?? inner).trim(); // [[path|alias]] → path
      const after = line.slice(close + 2).trim();
      const label = after.startsWith("|") ? after.slice(1).trim() : "";
      if (target !== "") out.push({ target, isLink: true, kind: attachmentKind(target, false), ...(label ? { label } : {}) });
      continue;
    }
    const pipe = line.indexOf("|");
    const left = (pipe >= 0 ? line.slice(0, pipe) : line).trim();
    const label = pipe >= 0 ? line.slice(pipe + 1).trim() : "";
    if (isUrl(left)) out.push({ target: left, isLink: false, kind: attachmentKind(left, true), ...(label ? { label } : {}) });
    // Non-link, non-URL lines are ignored.
  }
  return out;
}

/** Serialise attachments back to block lines. */
export function serializeAttachments(attachments: readonly Attachment[]): string {
  return attachments
    .map((a) => {
      const ref = a.isLink ? `[[${a.target}]]` : a.target;
      return a.label && a.label.trim() !== "" ? `${ref} | ${a.label.trim()}` : ref;
    })
    .join("\n");
}

/** A short human label for an attachment (its label, else the file basename or URL host). */
export function attachmentName(a: Attachment): string {
  if (a.label && a.label.trim() !== "") return a.label.trim();
  if (a.isLink) {
    const base = a.target.split("/").pop() ?? a.target;
    return base.replace(/\.[^.]+$/, "");
  }
  try {
    return new URL(a.target).hostname.replace(/^www\./, "");
  } catch {
    return a.target;
  }
}

/** Extract the raw content of every ```kvs-paper``` block in a note. */
export function extractKvsPaperBlocks(markdown: string): string[] {
  const blocks: string[] = [];
  let inBlock = false;
  let buf: string[] = [];
  for (const line of markdown.split("\n")) {
    const t = line.trim();
    if (!inBlock && /^```+\s*kvs-paper\s*$/.test(t)) {
      inBlock = true;
      buf = [];
      continue;
    }
    if (inBlock && /^```+\s*$/.test(t)) {
      inBlock = false;
      blocks.push(buf.join("\n"));
      continue;
    }
    if (inBlock) buf.push(line);
  }
  return blocks;
}

/** All attachments declared across a note's kvs-paper blocks. */
export function allPaperAttachments(markdown: string): Attachment[] {
  return extractKvsPaperBlocks(markdown).flatMap((b) => parseAttachments(b));
}
