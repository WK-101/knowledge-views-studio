/**
 * Normalizing captured values.
 *
 * Content from the wider web is not tidy: dates arrive in half a dozen national orders, numbers disagree
 * about which separator means "decimal", and text carries invisible characters that break matching later.
 * This is the layer that settles all of it before a value reaches a column.
 *
 * The governing rule is **never guess when the answer is genuinely ambiguous**. `03/07/2026` is the third of
 * July in most of the world and the seventh of March in the United States, and nothing in the string says
 * which. Silently picking one would write a wrong date that looks right — the worst kind of error, because
 * nobody ever spots it. So ambiguous input is returned untouched and surfaces in the review step, where a
 * person can see it and decide. Losing a little automation is a fair price for never inventing data.
 */

/** Zero-width characters that are safe to drop: a space and a byte-order mark, neither of which is text. */
const INVISIBLE = /[\u200B\uFEFF]/g;
/**
 * Whitespace that isn't ASCII space: non-breaking, en/em quad family, ideographic. Deliberately NOT
 * including U+200C/U+200D (zero-width non-joiner/joiner) — those carry meaning in Persian, Devanagari and
 * emoji sequences, and stripping them would corrupt the very scripts this is meant to serve.
 */
const ODD_SPACE = /[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]/g;

/**
 * Canonical form for any captured text: composed Unicode, no invisibles, single spaces, trimmed.
 *
 * The NFC pass matters more than it looks. The same accented or Hangul character can arrive either composed
 * or decomposed depending on the source platform, and the two are different strings to every comparison in
 * the codebase — so without this, duplicate detection and enum matching quietly fail on non-English content.
 */
export function normalizeText(raw: string): string {
  return raw
    .normalize("NFC")
    .replace(INVISIBLE, "")
    .replace(ODD_SPACE, " ")
    .replace(/\s+/g, " ")
    .trim();
}

const MONTHS: Readonly<Record<string, number>> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
  jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};

function iso(year: number, month: number, day: number): string | null {
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const mm = String(month).padStart(2, "0");
  const dd = String(day).padStart(2, "0");
  return `${String(year).padStart(4, "0")}-${mm}-${dd}`;
}

/**
 * Parse a date into ISO `YYYY-MM-DD`, or return the input unchanged when its meaning isn't certain.
 *
 * Recognised: ISO (with or without a time part), CJK `2026年7月18日`, English month names in either order,
 * and numeric dates whose day component exceeds twelve — which is what makes the order self-evident.
 * A bare numeric date where both components could be a month is left alone on purpose.
 */
export function normalizeDate(raw: string): string {
  const text = normalizeText(raw);
  if (text === "") return "";

  // ISO, optionally with a time part: take the date. This is what OpenGraph and Schema.org emit, so it's
  // the common case for anything captured from a web page.
  const isoMatch = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T\s].*)?$/.exec(text);
  if (isoMatch) {
    const out = iso(Number(isoMatch[1]), Number(isoMatch[2]), Number(isoMatch[3]));
    if (out) return out;
  }

  // CJK: 2026年7月18日
  const cjk = /^(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日?$/.exec(text);
  if (cjk) {
    const out = iso(Number(cjk[1]), Number(cjk[2]), Number(cjk[3]));
    if (out) return out;
  }

  // Year-first with slashes or dots: 2026/07/18, 2026.07.18
  const yearFirst = /^(\d{4})[./](\d{1,2})[./](\d{1,2})$/.exec(text);
  if (yearFirst) {
    const out = iso(Number(yearFirst[1]), Number(yearFirst[2]), Number(yearFirst[3]));
    if (out) return out;
  }

  // English month names, either order: "18 July 2026", "July 18, 2026", "18 Jul 2026".
  const dayMonth = /^(\d{1,2})\s+([A-Za-z]{3,})\.?,?\s+(\d{4})$/.exec(text);
  if (dayMonth) {
    const month = MONTHS[dayMonth[2]!.slice(0, 3).toLowerCase()];
    if (month !== undefined) {
      const out = iso(Number(dayMonth[3]), month, Number(dayMonth[1]));
      if (out) return out;
    }
  }
  const monthDay = /^([A-Za-z]{3,})\.?\s+(\d{1,2}),?\s+(\d{4})$/.exec(text);
  if (monthDay) {
    const month = MONTHS[monthDay[1]!.slice(0, 3).toLowerCase()];
    if (month !== undefined) {
      const out = iso(Number(monthDay[3]), month, Number(monthDay[2]));
      if (out) return out;
    }
  }

  // Numeric day/month/year. Only resolvable when one component is above twelve and so can only be a day.
  const numeric = /^(\d{1,2})[./-](\d{1,2})[./-](\d{4})$/.exec(text);
  if (numeric) {
    const a = Number(numeric[1]);
    const b = Number(numeric[2]);
    const year = Number(numeric[3]);
    if (a > 12 && b <= 12) return iso(year, b, a) ?? text; // day first
    if (b > 12 && a <= 12) return iso(year, a, b) ?? text; // month first
    return text; // both plausible as a month — say nothing rather than guess wrong
  }

  return text;
}

/**
 * Parse a number written to any national convention into a plain decimal string, or return it unchanged
 * when the grouping is ambiguous.
 *
 * When both separators appear, whichever comes last is the decimal point — that single rule resolves
 * `1,234.56` and `1.234,56` correctly without knowing the locale. With only one separator we fall back to
 * digit counting: exactly three digits after it is a thousands group, anything else is a fraction.
 */
export function normalizeNumber(raw: string): string {
  const cleaned = normalizeText(raw);
  if (cleaned === "") return "";
  const text = cleaned.replace(/[^\d.,\-+]/g, "");
  // Stripping everything means this was never a number. Hand back what arrived rather than an empty cell —
  // blanking it would destroy the captured value instead of letting someone see and fix it.
  if (text === "") return cleaned;

  const lastDot = text.lastIndexOf(".");
  const lastComma = text.lastIndexOf(",");
  let out = text;

  if (lastDot >= 0 && lastComma >= 0) {
    const decimalAt = Math.max(lastDot, lastComma);
    const groupChar = decimalAt === lastDot ? "," : ".";
    out = text.split(groupChar).join("");
    out = out.replace(decimalAt === lastDot ? /\./g : /,/g, ".");
  } else if (lastDot >= 0 || lastComma >= 0) {
    const sep = lastDot >= 0 ? "." : ",";
    const at = lastDot >= 0 ? lastDot : lastComma;
    const after = text.length - at - 1;
    const occurrences = text.split(sep).length - 1;
    if (after === 3 && occurrences >= 1) out = text.split(sep).join(""); // thousands group
    else out = text.split(sep).join("."); // a fraction
  }

  return /^[+-]?\d*\.?\d+$/.test(out) ? out : normalizeText(raw);
}

/** Normalize a value for the column type it's headed for. Unknown types get the text treatment. */
export function normalizeForType(raw: string, typeId: string): string {
  switch (typeId) {
    case "date":
      return normalizeDate(raw);
    case "number":
    case "rating":
      return normalizeNumber(raw);
    case "checkbox": {
      const t = normalizeText(raw).toLowerCase();
      if (["true", "yes", "1", "x", "✓"].includes(t)) return "true";
      if (["false", "no", "0", ""].includes(t)) return "false";
      return normalizeText(raw);
    }
    case "tags":
    case "list": {
      // Accept comma, semicolon, or the CJK enumeration comma as separators.
      const parts = normalizeText(raw)
        .split(/[,;、]/)
        .map((p) => p.trim())
        .filter((p) => p !== "");
      return parts.join(", ");
    }
    default:
      return normalizeText(raw);
  }
}
