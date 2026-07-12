import type { ColumnType } from "../column-type";

/**
 * Split a list value into items on commas, semicolons or newlines — keeping multi-word items whole.
 * This is the key difference from `tags`, which also splits on spaces: "New York, Rome" is two list
 * items but four tags. Use `list` for enumerations (ingredients, attendees, steps); `tags` for labels.
 */
export function splitList(raw: string): string[] {
  return String(raw ?? "")
    .split(/[,;\n]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

export const LIST: ColumnType = {
  id: "list",
  label: "List",
  operators: ["contains", "not-contains", "is-empty", "is-not-empty"],
  isEmpty: (raw) => splitList(raw).length === 0,
  toComparable: (raw) => ({ kind: "string", value: splitList(raw).join(" ").toLowerCase() }),
  toPlainText: (raw) => splitList(raw).join(", "),
  validate: () => null,
};
