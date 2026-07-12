/**
 * A semantic role for a column, independent of its data type. Roles let views pick
 * sensible defaults — the board's group-by, the calendar's date, a card's title —
 * instead of guessing positionally. A role can be set explicitly on a column, or
 * inferred from its type and header name.
 */
export type FieldRole = "none" | "title" | "status" | "date" | "tags" | "priority";

export const FIELD_ROLES: readonly FieldRole[] = ["none", "title", "status", "date", "tags", "priority"];

const DATE_NAME = /\b(date|due|deadline|scheduled|start|end|when)\b/;
const TAGS_NAME = /^(tags?|labels?|topics?)$/;
const PRIORITY_NAME = /\b(priority|importance|urgency|severity)\b/;
const STATUS_NAME = /\b(status|state|stage|phase|progress|column|kanban)\b/;
const TITLE_NAME = /^(title|name|task|subject|summary|item|headline)$/;

/** Best-guess role from a column's type id and header name (no explicit role set). */
export function inferFieldRole(typeId: string, name: string): FieldRole {
  const n = name.trim().toLowerCase();
  if (typeId === "date" || DATE_NAME.test(n)) return "date";
  if (typeId === "tags" || TAGS_NAME.test(n)) return "tags";
  if (PRIORITY_NAME.test(n)) return "priority";
  if (STATUS_NAME.test(n)) return "status";
  if (TITLE_NAME.test(n)) return "title";
  return "none";
}
