import type { CellEdit, CellWriteResult, WriteFailure } from "./source-writer";

const escapeRegex = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Replace the value of an inline field (`key:: value`) — bracketed or line form,
 * first occurrence — with the new value. A missing key fails cleanly; nothing is
 * appended, so line counts never shift for co-located sources.
 */
export function applyInlineFieldEdits(content: string, edits: readonly CellEdit[]): CellWriteResult {
  let text = content;
  const failures: WriteFailure[] = [];
  let applied = 0;

  for (const edit of edits) {
    const key = edit.column.trim();
    if (key === "") continue;
    const value = edit.value;
    const bracket = new RegExp(`([[(]\\s*${escapeRegex(key)}\\s*::\\s*)([^\\])]*?)(\\s*[\\])])`, "i");
    const line = new RegExp(`(^\\s*(?:[-*+]\\s+)?${escapeRegex(key)}\\s*::\\s*)(.*)$`, "im");

    if (bracket.test(text)) {
      text = text.replace(bracket, (_full, prefix: string, _old: string, suffix: string) => `${prefix}${value}${suffix}`);
      applied++;
    } else if (line.test(text)) {
      text = text.replace(line, (_full, prefix: string) => `${prefix}${value}`);
      applied++;
    } else {
      failures.push({ provenance: edit.provenance, column: edit.column, reason: `Inline field "${key}" not found.` });
    }
  }

  return { content: text, applied, failures };
}
