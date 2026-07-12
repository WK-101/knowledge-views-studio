import type { ColumnType } from "../column-type";

const TRUE_VALUES: ReadonlySet<string> = new Set([
  "true",
  "yes",
  "y",
  "x",
  "✓",
  "✔",
  "checked",
  "done",
  "1",
]);

export function toBoolean(raw: string): boolean {
  return TRUE_VALUES.has(String(raw ?? "").trim().toLowerCase());
}

export const CHECKBOX: ColumnType = {
  id: "checkbox",
  label: "Checkbox",
  operators: ["equals", "not-equals", "is-empty", "is-not-empty"],
  isEmpty: (raw) => String(raw ?? "").trim() === "",
  toComparable: (raw) => ({ kind: "number", value: toBoolean(raw) ? 1 : 0 }),
  toPlainText: (raw) => (toBoolean(raw) ? "true" : "false"),
  validate: () => null,
};
