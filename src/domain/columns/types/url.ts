import type { ColumnType } from "../column-type";

export const URL_TYPE: ColumnType = {
  id: "url",
  label: "URL",
  operators: ["contains", "not-contains", "equals", "not-equals", "is-empty", "is-not-empty"],
  isEmpty: (raw) => String(raw ?? "").trim() === "",
  toComparable: (raw) => ({ kind: "string", value: String(raw ?? "").trim().toLowerCase() }),
  toPlainText: (raw) => String(raw ?? "").trim(),
  validate: (raw) => {
    const s = String(raw ?? "").trim();
    if (s === "") return null;
    const looksLikeUrl = /^(https?:\/\/|obsidian:\/\/|mailto:)/i.test(s) || !/\s/.test(s);
    return looksLikeUrl ? null : "Does not look like a URL";
  },
};
