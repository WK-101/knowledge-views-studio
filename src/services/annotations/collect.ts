import { ANNOTATIONS_START, ANNOTATIONS_END } from "./render";

/** A highlight parsed back out of a note's rendered annotations region. */
export interface ParsedHighlight {
  readonly blockId: string; // e.g. "anno-1a2b3c4d"
  readonly page: number;
  readonly label: string; // the middle meta field as rendered (theme, or colour name)
  readonly text: string;
  readonly comment: string;
}

export interface NoteHighlights {
  /** Note reference for transclusion (basename or path, no extension). */
  readonly note: string;
  readonly highlights: readonly ParsedHighlight[];
}

const HEADER = /^>\s*\[![^\]]+\]\s*p\.(\d+)\s*·\s*([^·]+?)\s*·\s*[^^]*\^(\S+)\s*$/;

/** Parse the callouts inside a note's managed annotations region (or the whole note if unmarked). */
export function parseAnnotationRegion(markdown: string): ParsedHighlight[] {
  const start = markdown.indexOf(ANNOTATIONS_START);
  const end = markdown.indexOf(ANNOTATIONS_END);
  const body = start >= 0 && end > start ? markdown.slice(start + ANNOTATIONS_START.length, end) : markdown;
  const lines = body.split("\n");
  const out: ParsedHighlight[] = [];
  let cur: { page: number; label: string; blockId: string; text: string[]; comment: string[] } | null = null;
  const flush = (): void => {
    if (!cur) return;
    out.push({ blockId: cur.blockId, page: cur.page, label: cur.label, text: cur.text.join(" ").replace(/\s+/g, " ").trim(), comment: cur.comment.join(" ").replace(/\s+/g, " ").trim() });
    cur = null;
  };
  for (const line of lines) {
    const h = HEADER.exec(line);
    if (h) {
      flush();
      cur = { page: Number(h[1]), label: (h[2] ?? "").trim(), blockId: (h[3] ?? "").trim(), text: [], comment: [] };
      continue;
    }
    if (!cur) continue;
    const t = line.replace(/^>\s?/, "");
    if (/^\s*$/.test(line) || !line.trimStart().startsWith(">")) {
      flush();
      continue;
    }
    if (t.startsWith("[Open")) continue;
    if (t.startsWith("**Note:**")) cur.comment.push(t.replace(/^\*\*Note:\*\*\s*/, ""));
    else cur.text.push(t);
  }
  flush();
  return out;
}

/** Remove the single annotation callout carrying `^<blockId>` (plus its trailing blank line). Pure. */
export function removeAnnotationCallout(content: string, blockId: string): string {
  const marker = `^${blockId.replace(/^\^/, "")}`;
  const lines = content.split("\n");
  const out: string[] = [];
  let skipping = false;
  for (const line of lines) {
    if (!skipping && line.includes(marker) && line.trimStart().startsWith(">")) {
      skipping = true;
      continue;
    }
    if (skipping) {
      if (line.trimStart().startsWith(">")) continue;
      skipping = false;
      if (line.trim() === "") continue; // eat one trailing blank
    }
    out.push(line);
  }
  return out.join("\n");
}

/** Replace the callout carrying `^<blockId>` with new markdown (keeping its position). Pure. */
export function replaceAnnotationCallout(content: string, blockId: string, newCallout: string): string {
  const marker = `^${blockId.replace(/^\^/, "")}`;
  const lines = content.split("\n");
  const out: string[] = [];
  let skipping = false;
  for (const line of lines) {
    if (!skipping && line.includes(marker) && line.trimStart().startsWith(">")) {
      skipping = true;
      out.push(...newCallout.split("\n"));
      continue;
    }
    if (skipping) {
      if (line.trimStart().startsWith(">")) continue;
      skipping = false;
    }
    out.push(line);
  }
  return out.join("\n");
}

/** The source label rendered in a callout's meta ("PDF" / "Zotero" / "Note"), from its title text. */
export function calloutSourceLabel(titleText: string): string {
  const parts = titleText.split("·").map((p) => p.trim());
  return parts[parts.length - 1] ?? "";
}
export function buildThemeSynthesis(notes: readonly NoteHighlights[], opts: { title?: string; embed?: boolean } = {}): string {
  const byTheme = new Map<string, { note: string; h: ParsedHighlight }[]>();
  let total = 0;
  for (const n of notes) {
    for (const h of n.highlights) {
      total++;
      const arr = byTheme.get(h.label) ?? [];
      arr.push({ note: n.note, h });
      byTheme.set(h.label, arr);
    }
  }
  const embed = opts.embed !== false;
  const heading = opts.title ?? "Highlight synthesis";
  const parts: string[] = [`# ${heading}`, "", `*${total} highlight${total === 1 ? "" : "s"} across ${notes.filter((n) => n.highlights.length > 0).length} note${notes.length === 1 ? "" : "s"}, grouped by theme.*`, ""];
  for (const theme of [...byTheme.keys()].sort((a, b) => a.localeCompare(b))) {
    parts.push(`## ${theme}`, "");
    for (const { note, h } of byTheme.get(theme)!) {
      if (embed) parts.push(`![[${note}#^${h.blockId}]]`, "");
      else {
        const line = h.text !== "" ? h.text : h.comment;
        parts.push(`- ${line} — [[${note}#^${h.blockId}|p.${h.page}]]`);
      }
    }
    if (!embed) parts.push("");
  }
  return parts.join("\n").trimEnd() + "\n";
}
