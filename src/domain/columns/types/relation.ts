import type { ColumnType } from "../column-type";
import { parseWikiLinks } from "./link";

/** The note names/paths a relation cell points to (link targets, ignoring aliases). */
export function relationTargets(raw: string): string[] {
  return parseWikiLinks(raw).map((link) => link.target);
}

/**
 * A relation column holds one or more `[[note]]` wiki-links to other notes. It is
 * stored as real Markdown links, so relations show up in the graph and survive
 * export; rollup columns aggregate fields across the rows of the linked notes.
 */
export const RELATION: ColumnType = {
  id: "relation",
  label: "Relation (note links)",
  operators: ["contains", "not-contains", "is-empty", "is-not-empty"],
  isEmpty: (raw) => relationTargets(raw).length === 0,
  toComparable: (raw) => ({ kind: "string", value: relationTargets(raw).join(", ").toLowerCase() }),
  toPlainText: (raw) => parseWikiLinks(raw).map((link) => link.alias ?? link.target).join(", "),
  validate: () => null,
};
