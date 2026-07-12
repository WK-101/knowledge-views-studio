import type { ColumnType } from "../column-type";

export function parseNumber(raw: string): number | null {
  const match = String(raw ?? "")
    .replace(/,/g, "")
    .match(/-?\d+(\.\d+)?/);
  if (!match) return null;
  const n = Number(match[0]);
  return Number.isFinite(n) ? n : null;
}

export const NUMBER: ColumnType = {
  id: "number",
  label: "Number",
  operators: ["equals", "not-equals", "gt", "gte", "lt", "lte", "is-empty", "is-not-empty"],
  isEmpty: (raw) => String(raw ?? "").trim() === "",
  toComparable: (raw) => {
    const n = parseNumber(raw);
    return n === null
      ? { kind: "string", value: String(raw ?? "").trim().toLowerCase() }
      : { kind: "number", value: n };
  },
  toPlainText: (raw) => String(raw ?? "").trim(),
  validate: (raw) =>
    String(raw ?? "").trim() === "" || parseNumber(raw) !== null ? null : "Not a number",
};
