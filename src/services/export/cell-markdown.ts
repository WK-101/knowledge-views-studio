// Markdown → block model for exports. This goes beyond inline formatting: it parses
// block structure (paragraphs, headings, nested bullet/numbered lists, task lists,
// blockquotes, fenced code, horizontal rules) so exported PDFs and Word docs render
// rich-text cells faithfully. It is not a full CommonMark implementation, but covers the
// structures people actually write in notes, including multi-level lists.

export interface CellToken {
  readonly kind: "text" | "link" | "image" | "break";
  readonly value?: string;
  readonly href?: string;
  readonly src?: string;
  readonly bold?: boolean;
  readonly italic?: boolean;
  readonly code?: boolean;
  readonly strike?: boolean;
}

export interface ListItem {
  inline: CellToken[];
  /** undefined = not a task item; true/false = checked/unchecked checkbox. */
  task?: boolean;
  children: Block[];
}

export type Block =
  | { type: "p"; inline: CellToken[] }
  | { type: "heading"; level: number; inline: CellToken[] }
  | { type: "list"; ordered: boolean; start: number; items: ListItem[] }
  | { type: "quote"; blocks: Block[] }
  | { type: "hr" }
  | { type: "code"; text: string };

const INLINE_SOURCE =
  "(!\\[\\[[^\\]]+?\\]\\])|" +
  "(!\\[[^\\]]*?\\]\\([^)]+?\\))|" +
  "(\\[[^\\]]+?\\]\\([^)]+?\\))|" +
  "(`[^`]+?`)|" +
  "(\\*\\*[^*]+?\\*\\*)|" +
  "(__[^_]+?__)|" +
  "(~~[^~]+?~~)|" +
  "(\\*[^*]+?\\*)|" +
  "(_[^_]+?_)";

function tokenizeInline(text: string, images: ReadonlyMap<string, string>, out: CellToken[]): void {
  const re = new RegExp(INLINE_SOURCE, "g");
  let last = 0;
  let match: RegExpExecArray | null;
  const push = (value: string, extra: Partial<CellToken> = {}): void => {
    if (value !== "") out.push({ kind: "text", value, ...extra });
  };
  while ((match = re.exec(text)) !== null) {
    if (match.index > last) push(text.slice(last, match.index));
    const tok = match[0];
    if (match[1] || match[2]) {
      const url = images.get(tok);
      if (url) out.push({ kind: "image", src: url });
      else push(tok);
    } else if (match[3]) {
      const link = /^\[([^\]]+?)\]\(([^)]+?)\)$/.exec(tok);
      if (link) out.push({ kind: "link", value: link[1] ?? "", href: link[2] ?? "" });
      else push(tok);
    } else if (match[4]) {
      push(tok.slice(1, -1), { code: true });
    } else if (match[5] || match[6]) {
      push(tok.slice(2, -2), { bold: true });
    } else if (match[7]) {
      push(tok.slice(2, -2), { strike: true });
    } else if (match[8] || match[9]) {
      push(tok.slice(1, -1), { italic: true });
    }
    last = match.index + tok.length;
  }
  if (last < text.length) push(text.slice(last));
}

/** Tokenize text that may contain soft line breaks (\n), emitting break tokens between them. */
function inlineWithBreaks(text: string, images: ReadonlyMap<string, string>): CellToken[] {
  const out: CellToken[] = [];
  text.split("\n").forEach((segment, index) => {
    if (index > 0) out.push({ kind: "break" });
    tokenizeInline(segment, images, out);
  });
  return out;
}

const LIST_RE = /^(\s*)(?:([-*+])|(\d+)[.)])\s+(.*)$/;
const leadingWidth = (line: string): number => (/^\s*/.exec(line)?.[0] ?? "").replace(/\t/g, "  ").length;
const isListLine = (line: string): boolean => LIST_RE.test(line);

interface RawItem {
  indent: number;
  ordered: boolean;
  start: number;
  content: string;
  task?: boolean;
}

/**
 * Fold flat list lines into nested lists using indentation. A change of marker type at the
 * same level (bullet ↔ numbered) starts a new sibling list, matching Markdown semantics, so
 * a bullet list directly followed by a numbered list stays two lists (each numbered correctly).
 * Returns the top-level list blocks (usually one, but more when types alternate at the root).
 */
function buildLists(rawItems: RawItem[], images: ReadonlyMap<string, string>): Block[] {
  interface Frame {
    indent: number;
    ordered: boolean;
    list: Block & { type: "list" };
  }
  const roots: Block[] = [];
  const stack: Frame[] = [];

  const attach = (raw: RawItem, parentItem: ListItem | null): Block & { type: "list" } => {
    const list: Block & { type: "list" } = { type: "list", ordered: raw.ordered, start: raw.start, items: [] };
    if (parentItem) parentItem.children.push(list);
    else roots.push(list);
    return list;
  };

  for (const raw of rawItems) {
    const item: ListItem = { inline: inlineWithBreaks(raw.content, images), children: [] };
    if (raw.task !== undefined) item.task = raw.task;

    while (stack.length > 0 && raw.indent < (stack[stack.length - 1]?.indent ?? 0)) stack.pop();
    const top = stack[stack.length - 1];

    if (top && raw.indent === top.indent) {
      if (raw.ordered === top.ordered) {
        top.list.items.push(item);
      } else {
        // Same visual level, different marker type → a new sibling list under the same parent.
        stack.pop();
        const parent = stack[stack.length - 1];
        const parentItem = parent ? (parent.list.items[parent.list.items.length - 1] ?? null) : null;
        const list = attach(raw, parentItem);
        list.items.push(item);
        stack.push({ indent: raw.indent, ordered: raw.ordered, list });
      }
    } else if (top && raw.indent > top.indent) {
      const parentItem = top.list.items[top.list.items.length - 1] ?? null;
      const list = attach(raw, parentItem);
      list.items.push(item);
      stack.push({ indent: raw.indent, ordered: raw.ordered, list });
    } else {
      const list = attach(raw, null);
      list.items.push(item);
      stack.push({ indent: raw.indent, ordered: raw.ordered, list });
    }
  }
  return roots;
}

/** Parse a raw cell value into render blocks. `images` maps embeds to resolved data URLs. */
export function parseCellBlocks(raw: string, images: ReadonlyMap<string, string>): Block[] {
  const lines = raw.replace(/<br\s*\/?>/gi, "\n").split("\n");
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i] ?? "";
    const trimmed = line.trim();

    if (trimmed === "") {
      i++;
      continue;
    }

    // Fenced code block.
    if (/^```/.test(trimmed)) {
      const body: string[] = [];
      i++;
      while (i < lines.length && !/^```/.test((lines[i] ?? "").trim())) {
        body.push(lines[i] ?? "");
        i++;
      }
      if (i < lines.length) i++;
      blocks.push({ type: "code", text: body.join("\n") });
      continue;
    }

    // Horizontal rule (---, ***, ___).
    if (/^([-*_])(\s*\1){2,}$/.test(trimmed)) {
      blocks.push({ type: "hr" });
      i++;
      continue;
    }

    // Heading.
    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed);
    if (heading) {
      blocks.push({ type: "heading", level: heading[1]!.length, inline: inlineWithBreaks(heading[2] ?? "", images) });
      i++;
      continue;
    }

    // Blockquote (consecutive > lines, parsed recursively).
    if (/^>\s?/.test(trimmed)) {
      const quoted: string[] = [];
      while (i < lines.length && /^>\s?/.test((lines[i] ?? "").trim())) {
        quoted.push((lines[i] ?? "").trim().replace(/^>\s?/, ""));
        i++;
      }
      blocks.push({ type: "quote", blocks: parseCellBlocks(quoted.join("\n"), images) });
      continue;
    }

    // List (consecutive list lines + their indented continuations).
    if (isListLine(line)) {
      const rawItems: RawItem[] = [];
      while (i < lines.length) {
        const cur = lines[i] ?? "";
        const m = LIST_RE.exec(cur);
        if (m) {
          let content = m[4] ?? "";
          let task: boolean | undefined;
          const t = /^\[([ xX])\]\s+(.*)$/.exec(content);
          if (t) {
            task = t[1]!.toLowerCase() === "x";
            content = t[2] ?? "";
          }
          const item: RawItem = {
            indent: (m[1] ?? "").replace(/\t/g, "  ").length,
            ordered: m[3] !== undefined,
            start: m[3] !== undefined ? Number(m[3]) : 1,
            content,
          };
          if (task !== undefined) item.task = task;
          rawItems.push(item);
          i++;
        } else if (cur.trim() !== "" && leadingWidth(cur) >= 2 && rawItems.length > 0) {
          rawItems[rawItems.length - 1]!.content += `\n${cur.trim()}`;
          i++;
        } else {
          break;
        }
      }
      blocks.push(...buildLists(rawItems, images));
      continue;
    }

    // Paragraph (consecutive plain lines joined with soft breaks).
    const paraLines: string[] = [line];
    i++;
    while (i < lines.length) {
      const cur = lines[i] ?? "";
      if (cur.trim() === "" || isListLine(cur) || /^(#{1,6})\s|^>\s?|^```/.test(cur.trim())) break;
      paraLines.push(cur);
      i++;
    }
    blocks.push({ type: "p", inline: inlineWithBreaks(paraLines.join("\n"), images) });
  }

  return blocks;
}

/** Any renderable Markdown structure (formatting, links, breaks, images, lists, quotes…). */
export function hasRenderableMarkdown(raw: string): boolean {
  return /<br\s*\/?>|[*_`~]|\[[^\]]+?\]\([^)]+?\)|^\s{0,3}#{1,6}\s|^\s*[-*+]\s|^\s*\d+[.)]\s|^\s*>|!\[\[|```/m.test(raw);
}

/** True when any block (recursively) contains an image — used to flag image cells. */
export function blocksHaveImage(blocks: readonly Block[]): boolean {
  for (const block of blocks) {
    if (block.type === "p" || block.type === "heading") {
      if (block.inline.some((t) => t.kind === "image")) return true;
    } else if (block.type === "list") {
      for (const item of block.items) {
        if (item.inline.some((t) => t.kind === "image")) return true;
        if (blocksHaveImage(item.children)) return true;
      }
    } else if (block.type === "quote") {
      if (blocksHaveImage(block.blocks)) return true;
    }
  }
  return false;
}
