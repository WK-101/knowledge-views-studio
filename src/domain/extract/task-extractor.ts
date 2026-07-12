import type { Row, RowProvenance } from "../model";
import type { ExtractionInput, SourceExtractor } from "./extractor";
import { fnv1a } from "../../util/hash";

export const TASK_EXTRACTOR_ID = "task";

const TASK_LINE = /^(\s*)([-*+])\s+\[([ xX])\]\s?(.*)$/;
const DUE = /(?:📅\s*|due:\s*|\[due::\s*)(\d{4}-\d{2}-\d{2})/;
const TAG = /#[\w/-]+/g;
const PRIORITY: ReadonlyArray<[string, string]> = [
  ["🔺", "highest"],
  ["⏫", "high"],
  ["🔼", "medium"],
  ["🔽", "low"],
  ["⏬", "lowest"],
];

/**
 * One row per Markdown task (`- [ ] …`). The checkbox becomes a `done` field and
 * common annotations (📅/due:/[due::] dates, #tags, priority emoji) are parsed into
 * their own columns — and every field writes back to the exact source line.
 */
export const taskExtractor: SourceExtractor = {
  id: TASK_EXTRACTOR_ID,
  label: "Tasks / checkboxes (one row per task)",
  extract({ file, content }: ExtractionInput): Row[] {
    const lines = content.split(/\r?\n/);
    const rows: Row[] = [];
    lines.forEach((line, index) => {
      const match = line.match(TASK_LINE);
      if (!match) return;
      const text = (match[4] ?? "").trim();
      const cells: Record<string, string> = {
        task: text,
        done: (match[3] ?? " ").toLowerCase() === "x" ? "true" : "false",
      };
      const due = text.match(DUE);
      if (due) cells.due = due[1] ?? "";
      const tags = text.match(TAG);
      if (tags) cells.tags = tags.join(" ");
      for (const [emoji, label] of PRIORITY) {
        if (text.includes(emoji)) {
          cells.priority = label;
          break;
        }
      }
      const provenance: RowProvenance = {
        filePath: file.filePath,
        extractor: TASK_EXTRACTOR_ID,
        locator: { line: index },
        fingerprint: fnv1a(line),
      };
      rows.push({ cells, file, provenance });
    });
    return rows;
  },
};
