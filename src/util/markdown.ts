/**
 * Pure Markdown / text helpers shared by the parser, extractors, column types,
 * and (later) renderers. No Obsidian imports — fully unit-testable.
 */

/** Collapse a raw cell's whitespace and line-break conventions into a clean form. */
export function normalizeCellWhitespace(raw: string): string {
  return String(raw ?? "")
    .replace(/\r\n/g, "\n")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/\u00a0/g, " ")
    .trim();
}

/** Strip a single leading and trailing table pipe, if present. */
export function stripOuterPipes(line: string): string {
  let s = String(line ?? "").trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|")) s = s.slice(0, -1);
  return s;
}

/**
 * Is this line a GFM table separator row (e.g. `| --- | :--: |`)?
 * Only called on the line *after* a pipe-bearing header, so a bare `---`
 * thematic break never triggers false table detection on its own.
 */
export function isTableSeparator(line: string): boolean {
  const s = String(line ?? "").trim();
  if (s === "" || !s.includes("-")) return false;
  if (!/^[|:\-\s]+$/.test(s)) return false;
  const cells = stripOuterPipes(s).split("|");
  return cells.length > 0 && cells.every((cell) => /^\s*:?-+:?\s*$/.test(cell));
}

/**
 * Split a Markdown table row into trimmed cell strings, without breaking on
 * pipes that live inside inline code, wikilinks `[[a|b]]`, link text/URLs, or
 * escaped `\|`. This is the conservative splitter the whole pipeline relies on.
 */
export function splitTableRow(line: string): string[] {
  const s = stripOuterPipes(line);
  const cells: string[] = [];
  let current = "";
  let inCode = false;
  let wiki = 0;
  let bracket = 0;
  let paren = 0;
  let escaped = false;

  for (let i = 0; i < s.length; i++) {
    const ch = s.charAt(i);
    const next = s.charAt(i + 1);

    if (escaped) {
      current += ch;
      escaped = false;
      continue;
    }
    if (ch === "\\") {
      // `\|` is GFM's way of putting a literal pipe in a cell — unescape it, so the value a filter,
      // sort, search or clipboard copy sees is the real one, not `a \| b`. `escapeTableCell` puts the
      // escape back on write, so the round-trip is stable. Every other backslash is left alone: it may
      // be a Windows path, a LaTeX command, or a regex, and mangling those would be its own bug.
      if (next === "|") {
        current += "|";
        i++;
        continue;
      }
      current += ch;
      escaped = true;
      continue;
    }
    if (ch === "`") {
      inCode = !inCode;
      current += ch;
      continue;
    }

    if (!inCode) {
      if (ch === "[" && next === "[") {
        wiki++;
        current += "[[";
        i++;
        continue;
      }
      if (ch === "]" && next === "]" && wiki > 0) {
        wiki--;
        current += "]]";
        i++;
        continue;
      }
      if (wiki === 0) {
        if (ch === "[") bracket++;
        else if (ch === "]" && bracket > 0) bracket--;
        else if (ch === "(") paren++;
        else if (ch === ")" && paren > 0) paren--;
      }
    }

    if (ch === "|" && !inCode && wiki === 0 && bracket === 0 && paren === 0) {
      cells.push(current.trim());
      current = "";
      continue;
    }
    current += ch;
  }

  cells.push(current.trim());
  return cells;
}

/** Project a raw cell to plain text (for search, sorting, and display fallback). */
export function stripInlineMarkdown(raw: string): string {
  return normalizeCellWhitespace(raw)
    .replace(/!\[\[([^\]]+)\]\]/g, "[image: $1]")
    .replace(/!\[[^\]]*\]\(([^)]+)\)/g, "[image: $1]")
    .replace(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g, (_m, target, alias) => String(alias ?? target))
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/[*_~`>#]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

/** Escape a value for safe insertion into a single Markdown table cell. */
export function escapeTableCell(raw: string): string {
  return normalizeCellWhitespace(raw)
    .replace(/\n{2,}/g, "<br><br>")
    .replace(/\n/g, "<br>")
    .replace(/\|/g, "\\|");
}

/** Extract internal `![[...]]` and Markdown `![](...)` image embeds from a cell. */
/** Decode a stored table-cell value for editing: turn <br> back into real newlines (no trimming, so
 *  the editor round-trips faithfully). Write-back re-encodes newlines via escapeTableCell. */
export function decodeCellText(raw: string): string {
  return String(raw ?? "").replace(/<br\s*\/?>/gi, "\n");
}

export function extractImageEmbeds(raw: string): string[] {
  const text = normalizeCellWhitespace(raw);
  const out: string[] = [];

  const internal = /!\[\[([^\]]+?)\]\]/g;
  let m: RegExpExecArray | null;
  while ((m = internal.exec(text)) !== null) {
    out.push(`![[${(m[1] ?? "").trim()}]]`);
  }

  const external = /!\[[^\]]*\]\(([^)]+)\)/g;
  while ((m = external.exec(text)) !== null) {
    out.push(`![](${(m[1] ?? "").trim()})`);
  }

  return out;
}
