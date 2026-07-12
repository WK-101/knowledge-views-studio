import type { Row, RowProvenance } from "../model";
import type { ExtractionInput, SourceExtractor } from "./extractor";
import { fnv1a } from "../../util/hash";

export const FRONTMATTER_EXTRACTOR_ID = "frontmatter";

const KEY_LINE = /^([A-Za-z0-9_][A-Za-z0-9_\- ]*):\s*(.*)$/;
const LIST_ITEM = /^\s*-\s+(.*)$/;

function stripQuotes(value: string): string {
  const s = value.trim();
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    return s.slice(1, -1);
  }
  return s;
}

/** Locate the leading `---` … `---` block; returns its inclusive line bounds and body. */
export function findFrontmatter(content: string): { start: number; end: number; body: string[] } | null {
  const lines = content.split(/\r?\n/);
  if ((lines[0] ?? "").trim() !== "---") return null;
  for (let i = 1; i < lines.length; i++) {
    if ((lines[i] ?? "").trim() === "---") return { start: 0, end: i, body: lines.slice(1, i) };
  }
  return null;
}

/** Parse a frontmatter body into flat string cells (scalars, inline/block lists, links). */
export function parseFrontmatter(body: readonly string[]): Record<string, string> {
  const cells: Record<string, string> = {};
  let i = 0;
  while (i < body.length) {
    const match = (body[i] ?? "").match(KEY_LINE);
    if (!match) {
      i++;
      continue;
    }
    const key = (match[1] ?? "").trim();
    const inline = (match[2] ?? "").trim();
    if (inline === "") {
      const items: string[] = [];
      let j = i + 1;
      for (; j < body.length; j++) {
        const item = (body[j] ?? "").match(LIST_ITEM);
        if (!item) break;
        items.push(stripQuotes(item[1] ?? ""));
      }
      cells[key] = items.join(", ");
      i = j;
      continue;
    }
    if (inline.startsWith("[") && inline.endsWith("]")) {
      cells[key] = inline
        .slice(1, -1)
        .split(",")
        .map((s) => stripQuotes(s))
        .filter((s) => s !== "")
        .join(", ");
    } else {
      cells[key] = stripQuotes(inline);
    }
    i++;
  }
  return cells;
}

/**
 * One row per note, sourced from its YAML frontmatter properties — the same data
 * model Obsidian Bases uses. Combined with the table extractor, a single view can
 * span frontmatter *and* in-body tables, and edits still write back to source.
 */
export const frontmatterExtractor: SourceExtractor = {
  id: FRONTMATTER_EXTRACTOR_ID,
  label: "Frontmatter properties (one row per note)",
  extract({ file, content }: ExtractionInput): Row[] {
    const fm = findFrontmatter(content);
    if (!fm) return [];
    const cells = parseFrontmatter(fm.body);
    if (Object.keys(cells).length === 0) return [];
    const provenance: RowProvenance = {
      filePath: file.filePath,
      extractor: FRONTMATTER_EXTRACTOR_ID,
      locator: { start: fm.start, end: fm.end },
      fingerprint: fnv1a(fm.body.join("\n")),
    };
    return [{ cells, file, provenance }];
  },
};
