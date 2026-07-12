import type { CellEdit, CellWriteResult } from "./source-writer";

const detectNewline = (content: string): string => (content.includes("\r\n") ? "\r\n" : "\n");
const KEY_LINE = /^([A-Za-z0-9_][A-Za-z0-9_\- ]*):\s*(.*)$/;
const LIST_ITEM = /^\s*-\s+/;

/** Quote a value unless it is a plain word/number/date that YAML reads literally. */
function formatValue(value: string): string {
  const s = String(value ?? "");
  if (s !== "" && /^[\w ./-]+$/.test(s)) return s;
  return JSON.stringify(s);
}

function locateBlock(lines: readonly string[]): { start: number; end: number } | null {
  if ((lines[0] ?? "").trim() !== "---") return null;
  for (let i = 1; i < lines.length; i++) {
    if ((lines[i] ?? "").trim() === "---") return { start: 0, end: i };
  }
  return null;
}

/**
 * Apply property edits to a note's YAML frontmatter. Scalars are replaced in
 * place; a missing key is inserted before the closing `---`; a missing block is
 * created at the top; and a list-valued key is converted to the new scalar
 * without orphaning its `- item` lines. Column name = property key.
 */
export function applyFrontmatterEdits(content: string, edits: readonly CellEdit[]): CellWriteResult {
  const nl = detectNewline(content);
  let lines = content.split(/\r?\n/);
  let applied = 0;

  for (const edit of edits) {
    const key = edit.column.trim();
    if (key === "") continue;
    const value = formatValue(edit.value);
    const block = locateBlock(lines);

    if (!block) {
      const rest = lines.length === 1 && (lines[0] ?? "") === "" ? [] : lines;
      const gap = rest.length > 0 ? [""] : [];
      lines = ["---", `${key}: ${value}`, "---", ...gap, ...rest];
      applied++;
      continue;
    }

    let keyLine = -1;
    for (let i = block.start + 1; i < block.end; i++) {
      const match = (lines[i] ?? "").match(KEY_LINE);
      if (match && (match[1] ?? "").trim().toLowerCase() === key.toLowerCase()) {
        keyLine = i;
        break;
      }
    }

    if (keyLine < 0) {
      lines.splice(block.end, 0, `${key}: ${value}`);
      applied++;
      continue;
    }

    // If the existing value is a block list, sweep its `- item` lines too.
    let removeTo = keyLine + 1;
    const inlineValue = ((lines[keyLine] ?? "").match(KEY_LINE)?.[2] ?? "").trim();
    if (inlineValue === "") {
      while (removeTo < block.end && LIST_ITEM.test(lines[removeTo] ?? "")) removeTo++;
    }
    lines.splice(keyLine, removeTo - keyLine, `${key}: ${value}`);
    applied++;
  }

  return { content: lines.join(nl), applied, failures: [] };
}
