import type { ColumnType } from "../column-type";
import { stripInlineMarkdown } from "../../../util/markdown";

export const TEXT: ColumnType = {
  id: "text",
  label: "Text",
  operators: [
    "contains",
    "not-contains",
    "equals",
    "not-equals",
    "starts-with",
    "ends-with",
    "is-empty",
    "is-not-empty",
    "regex",
  ],
  isEmpty: (raw) => stripInlineMarkdown(raw).length === 0,
  toComparable: (raw) => ({ kind: "string", value: stripInlineMarkdown(raw).toLowerCase() }),
  toPlainText: (raw) => stripInlineMarkdown(raw),
  validate: () => null,
};
