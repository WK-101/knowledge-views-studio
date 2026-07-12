import type { ColumnType } from "../column-type";
import { stripInlineMarkdown } from "../../../util/markdown";

/** Rich free-text column (the legacy "content" / "notes" roles). */
export const MARKDOWN: ColumnType = {
  id: "markdown",
  label: "Rich text",
  operators: [
    "contains",
    "not-contains",
    "equals",
    "not-equals",
    "is-empty",
    "is-not-empty",
    "regex",
  ],
  isEmpty: (raw) => stripInlineMarkdown(raw).length === 0,
  toComparable: (raw) => ({ kind: "string", value: stripInlineMarkdown(raw).toLowerCase() }),
  toPlainText: (raw) => stripInlineMarkdown(raw),
  validate: () => null,
};
