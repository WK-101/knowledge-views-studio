import type { ColumnType } from "../column-type";

/** Single-choice enum / status column. Options + colours come from ColumnConfig. */
export const SELECT: ColumnType = {
  id: "select",
  label: "Select",
  operators: ["equals", "not-equals", "contains", "not-contains", "is-empty", "is-not-empty"],
  isEmpty: (raw) => String(raw ?? "").trim() === "",
  toComparable: (raw) => ({ kind: "string", value: String(raw ?? "").trim().toLowerCase() }),
  toPlainText: (raw) => String(raw ?? "").trim(),
  validate: (raw, config) => {
    const value = String(raw ?? "").trim();
    if (value === "" || !config.options || config.options.length === 0) return null;
    const allowed = config.options.some(
      (opt) => opt.value.trim().toLowerCase() === value.toLowerCase(),
    );
    return allowed ? null : `"${value}" is not one of the allowed options`;
  },
};
