/**
 * Coerce an untrusted JSON value to a string without ever producing "[object Object]".
 *
 * `String(x)` on an object yields "[object Object]", which would then be written into a user's backup,
 * export, or restored row as if it were real data. These parsers read files and API responses we do not
 * control, so anything that isn't a primitive is not a string — it's a malformed field, and the fallback
 * is the honest answer.
 */
export function asString(value: unknown, fallback = ""): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (typeof value === "bigint") return value.toString();
  return fallback;
}
