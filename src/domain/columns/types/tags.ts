import type { ColumnType } from "../column-type";

export function splitTags(raw: string): string[] {
  return String(raw ?? "")
    .replace(/#/g, "")
    .split(/[,;\s]+/)
    .map((t) => t.trim())
    .filter((t) => t.length > 0);
}

export const TAGS: ColumnType = {
  id: "tags",
  label: "Tags",
  operators: ["contains", "not-contains", "is-empty", "is-not-empty"],
  isEmpty: (raw) => splitTags(raw).length === 0,
  toComparable: (raw) => ({ kind: "string", value: splitTags(raw).join(" ").toLowerCase() }),
  toPlainText: (raw) => splitTags(raw).join(", "),
  validate: () => null,
};
