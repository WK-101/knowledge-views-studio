import type { ColumnType } from "../column-type";

export function parseDateMs(raw: string): number | null {
  const s = String(raw ?? "").trim();
  if (s === "") return null;
  const ms = Date.parse(s);
  return Number.isFinite(ms) ? ms : null;
}

export const DATE: ColumnType = {
  id: "date",
  label: "Date",
  operators: ["equals", "not-equals", "gt", "gte", "lt", "lte", "is-empty", "is-not-empty"],
  isEmpty: (raw) => String(raw ?? "").trim() === "",
  toComparable: (raw) => {
    const ms = parseDateMs(raw);
    return ms === null
      ? { kind: "string", value: String(raw ?? "").trim().toLowerCase() }
      : { kind: "number", value: ms };
  },
  toPlainText: (raw) => String(raw ?? "").trim(),
  validate: (raw) =>
    String(raw ?? "").trim() === "" || parseDateMs(raw) !== null
      ? null
      : "Not a recognizable date",
};
