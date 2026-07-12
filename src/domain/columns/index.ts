import { ColumnTypeRegistry } from "./registry";
import { TEXT } from "./types/text";
import { NUMBER } from "./types/number";
import { DATE } from "./types/date";
import { SELECT } from "./types/select";
import { TAGS } from "./types/tags";
import { LIST } from "./types/list";
import { CHECKBOX } from "./types/checkbox";
import { RATING } from "./types/rating";
import { URL_TYPE } from "./types/url";
import { LINK } from "./types/link";
import { RELATION } from "./types/relation";
import { IMAGE } from "./types/image";
import { MARKDOWN } from "./types/markdown";
import { ACADEMIC_COLUMN_TYPES } from "./types/academic";
import type { ColumnType } from "./column-type";

/** All built-in types, in a sensible UI order (text first as the default). */
export const BUILT_IN_COLUMN_TYPES: readonly ColumnType[] = [
  TEXT,
  NUMBER,
  DATE,
  SELECT,
  TAGS,
  LIST,
  CHECKBOX,
  RATING,
  LINK,
  RELATION,
  URL_TYPE,
  IMAGE,
  MARKDOWN,
];

/** Build a registry preloaded with every built-in type, falling back to text. */
export function createDefaultColumnTypeRegistry(): ColumnTypeRegistry {
  const registry = new ColumnTypeRegistry(TEXT);
  for (const type of BUILT_IN_COLUMN_TYPES) {
    if (type.id !== TEXT.id) registry.register(type);
  }
  for (const type of ACADEMIC_COLUMN_TYPES) registry.register(type); // logic only; kept out of the type dropdown
  return registry;
}

export {
  TEXT,
  NUMBER,
  DATE,
  SELECT,
  TAGS,
  LIST,
  CHECKBOX,
  RATING,
  URL_TYPE,
  LINK,
  RELATION,
  IMAGE,
  MARKDOWN,
};
export * from "./field-role";
export * from "./defaults";
export * from "./types/academic";
export * from "./academic-fields";
