import type { ColumnType } from "../column-type";

/** Rating scale (number of stars). */
export const RATING_MAX = 10;

export function toRating(raw: string): number {
  const s = String(raw ?? "").trim();
  if (s === "") return 0;
  const stars = (s.match(/[★⭐✪]/g) ?? []).length;
  if (stars > 0) return stars;
  const numeric = s.replace(/,/g, "").match(/-?\d+(\.\d+)?/);
  if (numeric) {
    const n = Number(numeric[0]);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

export const RATING: ColumnType = {
  id: "rating",
  label: "Rating",
  operators: ["equals", "not-equals", "gt", "gte", "lt", "lte", "is-empty", "is-not-empty"],
  isEmpty: (raw) => String(raw ?? "").trim() === "",
  toComparable: (raw) => ({ kind: "number", value: toRating(raw) }),
  toPlainText: (raw) => String(toRating(raw)),
  validate: () => null,
};
