import { NullValue, type Value } from "obsidian";

/**
 * Flatten a Bases property {@link Value} to a plain string for the KVS row model.
 * Bases' own `toString()` already renders links, dates, numbers and lists
 * sensibly; null/absent values become the empty string.
 */
export function valueToString(value: Value | null): string {
  if (value === null || value instanceof NullValue) return "";
  return value.toString();
}
