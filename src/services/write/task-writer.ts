import type { RowProvenance } from "../../domain/index";
import { fnv1a } from "../../util/hash";
import type { CellEdit, CellWriteResult, WriteFailure } from "./source-writer";

const detectNewline = (content: string): string => (content.includes("\r\n") ? "\r\n" : "\n");
const TASK_PARTS = /^(\s*[-*+]\s+\[)([ xX])(\]\s?)(.*)$/;
const DUE_TOKEN = /(📅\s*|due:\s*|\[due::\s*)(\d{4}-\d{2}-\d{2})(\]?)/;
const TRUTHY = new Set(["true", "x", "yes", "1", "done", "checked"]);

/** Find a task line by fingerprint, preferring its recorded line if still intact. */
function locateLine(lines: readonly string[], provenance: RowProvenance): number {
  const at = Number(provenance.locator.line);
  if (Number.isInteger(at) && at >= 0 && at < lines.length && fnv1a(lines[at] ?? "") === provenance.fingerprint) {
    return at;
  }
  for (let i = 0; i < lines.length; i++) {
    if (fnv1a(lines[i] ?? "") === provenance.fingerprint) return i;
  }
  return -1;
}

function setDue(text: string, date: string): string {
  if (DUE_TOKEN.test(text)) {
    return text
      .replace(DUE_TOKEN, (_full, prefix: string, _date: string, suffix: string) =>
        date === "" ? "" : `${prefix}${date}${suffix}`,
      )
      .replace(/\s{2,}/g, " ")
      .trim();
  }
  return date === "" ? text : `${text} 📅 ${date}`;
}

/**
 * Apply edits to Markdown task lines. Supported columns: `done` (toggles the
 * checkbox), `task`/`text` (replaces the task text), and `due` (sets or clears the
 * due date). Any other column fails cleanly rather than mangling the line.
 */
export function applyTaskEdits(content: string, edits: readonly CellEdit[]): CellWriteResult {
  const nl = detectNewline(content);
  const lines = content.split(/\r?\n/);
  const failures: WriteFailure[] = [];
  let applied = 0;

  for (const edit of edits) {
    const index = locateLine(lines, edit.provenance);
    if (index < 0) {
      failures.push({ provenance: edit.provenance, column: edit.column, reason: "Task not found (it may have changed)." });
      continue;
    }
    const parts = (lines[index] ?? "").match(TASK_PARTS);
    if (!parts) {
      failures.push({ provenance: edit.provenance, column: edit.column, reason: "That line is no longer a task." });
      continue;
    }
    const [, pre, check, mid, text] = parts as unknown as [string, string, string, string, string];
    const column = edit.column.trim().toLowerCase();

    if (column === "done") {
      const done = TRUTHY.has(edit.value.trim().toLowerCase());
      lines[index] = `${pre}${done ? "x" : " "}${mid}${text}`;
      applied++;
    } else if (column === "task" || column === "text") {
      lines[index] = `${pre}${check}${mid}${edit.value}`;
      applied++;
    } else if (column === "due") {
      lines[index] = `${pre}${check}${mid}${setDue(text, edit.value.trim())}`;
      applied++;
    } else {
      failures.push({ provenance: edit.provenance, column: edit.column, reason: `Editing "${edit.column}" on a task isn't supported.` });
    }
  }

  return { content: lines.join(nl), applied, failures };
}
