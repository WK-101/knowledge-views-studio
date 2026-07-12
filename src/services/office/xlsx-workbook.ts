import { openOfficePackage, type OfficePackage } from "./office-package";

/**
 * Read-only view of an `.xlsx` workbook, built on {@link OfficePackage}. It resolves sheet names
 * to their real parts (via workbook rels — never by constructing `sheetN.xml`), reads a sheet into
 * a DENSE, reference-aligned grid (xlsx omits empty cells, so positional mapping would misalign
 * after any gap), resolves shared/inline strings, renders date-formatted serials as ISO dates, and
 * flags formula cells. Writing is intentionally out of scope for this slice.
 */
export interface SheetRef {
  readonly index: number;
  readonly name: string;
  readonly part: string;
}

export type XlsxCellKind = "number" | "string" | "boolean" | "date" | "formula" | "empty";

export interface XlsxCell {
  readonly ref: string; // "B5"
  readonly text: string; // display value (strings resolved, numbers stringified, dates as ISO)
  readonly kind: XlsxCellKind;
  readonly isFormula: boolean;
}

export interface XlsxWorkbook {
  sheets(): SheetRef[];
  /** Resolve by exact name, else 1-based sheet number, else (no selector) the first sheet. */
  resolveSheet(selector?: string | number): SheetRef | undefined;
  /** Rows × cells as a dense grid: grid[i] is Excel row i+1, cell c is Excel column c. */
  readSheet(sheet: SheetRef): XlsxCell[][];
}

// ---- XML helpers (regex-based; OOXML parts are well-formed) -----------------

function attrVal(attrs: string, name: string): string | undefined {
  const m = new RegExp(`(?:^|\\s)${name}="([^"]*)"`).exec(attrs);
  return m ? m[1] : undefined;
}

function decodeXml(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&amp;/g, "&");
}

/** Concatenate every <t>…</t> inside a fragment (handles rich-text runs). */
function concatText(fragment: string): string {
  let out = "";
  for (const m of fragment.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)) out += decodeXml(m[1] ?? "");
  return out;
}

function innerV(body: string): string {
  const m = /<v\b[^>]*>([\s\S]*?)<\/v>/.exec(body);
  return m ? m[1] ?? "" : "";
}

// ---- Column addressing: bijective base-26 (A=1 … Z=26, AA=27) ---------------

/** 0-based column index for letters; `A`→0, `Z`→25, `AA`→26 (a naive base-26 gets AA wrong). */
export function columnToIndex(letters: string): number {
  let n = 0;
  for (let i = 0; i < letters.length; i++) n = n * 26 + (letters.charCodeAt(i) - 64);
  return n - 1;
}

export function indexToColumn(index: number): string {
  let n = index + 1;
  let out = "";
  while (n > 0) {
    const rem = (n - 1) % 26;
    out = String.fromCharCode(65 + rem) + out;
    n = Math.floor((n - 1) / 26);
  }
  return out;
}

function parseRef(ref: string): { col: number; row: number } | undefined {
  const m = /^([A-Za-z]+)(\d+)$/.exec(ref);
  if (!m) return undefined;
  return { col: columnToIndex(m[1]!.toUpperCase()), row: parseInt(m[2]!, 10) };
}

// ---- Dates ------------------------------------------------------------------

const BUILTIN_DATE_FMT_IDS = new Set<number>([
  14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 45, 46, 47, 50, 51,
  52, 53, 54, 55, 56, 57, 58,
]);

function isDateFormatCode(code: string): boolean {
  // Strip quoted literals and [locale]/[color] tokens, then look for date/time tokens.
  const stripped = code.replace(/"[^"]*"/g, "").replace(/\[[^\]]*\]/g, "");
  return /[yd]/i.test(stripped) || /h/i.test(stripped);
}

const BUILTIN_PERCENT_FMT_IDS = new Set<number>([9, 10]);

function isPercentFormatCode(code: string): boolean {
  return code.replace(/"[^"]*"/g, "").replace(/\[[^\]]*\]/g, "").includes("%");
}

// Built-in currency/accounting format ids use the system currency; default the symbol to "$".
const BUILTIN_CURRENCY_FMT_IDS = new Set<number>([5, 6, 7, 8, 42, 44]);

/** The currency symbol in a format code, if any: handles "$"#,##0, [$€-407]#,##0, £#,##0, etc. */
function currencySymbolOf(code: string): string | null {
  const bracket = /\[\$([^\]-]+)-?[^\]]*\]/.exec(code);
  if (bracket?.[1]) return bracket[1];
  const sym = /[$€£¥₹₩₪₴₺₦R]/.exec(code.replace(/\[[^\]]*\]/g, ""));
  return sym ? sym[0] : null;
}

/** Render a number the way a currency format would: "$1,234.56". */
function formatCurrency(n: number, symbol: string): string {
  const body = Math.abs(n).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `${n < 0 ? "-" : ""}${symbol}${body}`;
}

/** Render a stored ratio as a percentage the way Excel would (0.1 → "10%"), trimming zeros. */
function formatPercent(n: number): string {
  const pct = (n * 100).toFixed(2).replace(/\.?0+$/, "");
  return `${pct}%`;
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** Excel serial → ISO date (or datetime if it carries a time), honouring the 1900/1904 systems. */
export function serialToIso(serial: number, date1904: boolean): string {
  let ms: number;
  if (date1904) {
    ms = Date.UTC(1904, 0, 1) + serial * 86400000; // 1904 system: serial 0 = 1904-01-01
  } else {
    // 1900 system: serial 1 = 1900-01-01. Excel wrongly treats 1900 as a leap year (the phantom
    // serial 60 = "1900-02-29"), so serials ≥ 60 are shifted back a day to land on real dates.
    const days = serial >= 60 ? serial - 1 : serial;
    ms = Date.UTC(1899, 11, 31) + days * 86400000;
  }
  const d = new Date(ms);
  const date = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  const hasTime = Math.abs(serial - Math.floor(serial)) > 1e-9;
  if (!hasTime) return date;
  return `${date}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
}

// ---- Workbook ---------------------------------------------------------------

interface Parsed {
  sheets: SheetRef[];
  sharedStrings: string[];
  /** Per style index (`s`), whether its number format is a date/time format. */
  styleIsDate: boolean[];
  /** Per style index (`s`), whether its number format is a percentage. */
  styleIsPercent: boolean[];
  styleCurrency: (string | null)[];
  date1904: boolean;
}

function parseStyles(pkg: OfficePackage): { date: boolean[]; percent: boolean[]; currency: (string | null)[] } {
  const xml = pkg.readText("xl/styles.xml");
  if (!xml) return { date: [], percent: [], currency: [] };
  const customDate = new Map<number, boolean>();
  const customPercent = new Map<number, boolean>();
  const customCurrency = new Map<number, string | null>();
  const fmts = /<numFmts\b[^>]*>([\s\S]*?)<\/numFmts>/.exec(xml);
  if (fmts) {
    for (const m of fmts[1]!.matchAll(/<numFmt\b([^>]*)\/?>/g)) {
      const id = Number(attrVal(m[1] ?? "", "numFmtId"));
      const raw = attrVal(m[1] ?? "", "formatCode");
      if (Number.isFinite(id) && raw !== undefined) {
        const code = decodeXml(raw);
        customDate.set(id, isDateFormatCode(code));
        customPercent.set(id, isPercentFormatCode(code));
        customCurrency.set(id, currencySymbolOf(code));
      }
    }
  }
  const cellXfsBlock = /<cellXfs\b[^>]*>([\s\S]*?)<\/cellXfs>/.exec(xml);
  if (!cellXfsBlock) return { date: [], percent: [], currency: [] };
  const date: boolean[] = [];
  const percent: boolean[] = [];
  const currency: (string | null)[] = [];
  for (const m of cellXfsBlock[1]!.matchAll(/<xf\b([^>]*?)(?:\/>|>[\s\S]*?<\/xf>)/g)) {
    const numFmtId = Number(attrVal(m[1] ?? "", "numFmtId") ?? "0");
    date.push(BUILTIN_DATE_FMT_IDS.has(numFmtId) || customDate.get(numFmtId) === true);
    percent.push(BUILTIN_PERCENT_FMT_IDS.has(numFmtId) || customPercent.get(numFmtId) === true);
    currency.push(customCurrency.get(numFmtId) ?? (BUILTIN_CURRENCY_FMT_IDS.has(numFmtId) ? "$" : null));
  }
  return { date, percent, currency };
}

function parseWorkbook(pkg: OfficePackage): { sheets: SheetRef[]; date1904: boolean } {
  const wb = pkg.readText("xl/workbook.xml") ?? "";
  const date1904 = /<workbookPr\b[^>]*\bdate1904="(1|true)"/i.test(wb);

  const rels = pkg.readText("xl/_rels/workbook.xml.rels") ?? "";
  const relMap = new Map<string, string>();
  for (const m of rels.matchAll(/<Relationship\b([^>]*)\/?>/g)) {
    const id = attrVal(m[1] ?? "", "Id");
    const target = attrVal(m[1] ?? "", "Target");
    if (id && target) relMap.set(id, target);
  }
  const resolvePart = (target: string): string => (target.startsWith("/") ? target.slice(1) : `xl/${target}`);

  const sheets: SheetRef[] = [];
  const sheetsBlock = /<sheets\b[^>]*>([\s\S]*?)<\/sheets>/.exec(wb);
  if (sheetsBlock) {
    let index = 0;
    for (const m of sheetsBlock[1]!.matchAll(/<sheet\b([^>]*)\/?>/g)) {
      const name = decodeXml(attrVal(m[1] ?? "", "name") ?? `Sheet${index + 1}`);
      const rid = attrVal(m[1] ?? "", "r:id") ?? attrVal(m[1] ?? "", "id");
      const target = rid ? relMap.get(rid) : undefined;
      const part = target ? resolvePart(target) : `xl/worksheets/sheet${index + 1}.xml`;
      sheets.push({ index, name, part });
      index++;
    }
  }
  return { sheets, date1904 };
}

function parseSharedStrings(pkg: OfficePackage): string[] {
  const xml = pkg.readText("xl/sharedStrings.xml");
  if (!xml) return [];
  const out: string[] = [];
  for (const m of xml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)) out.push(concatText(m[1] ?? ""));
  return out;
}

function makeWorkbook(pkg: OfficePackage, parsed: Parsed): XlsxWorkbook {
  const resolveCell = (ref: string, attrs: string, body: string): XlsxCell => {
    const t = attrVal(attrs, "t");
    const s = attrVal(attrs, "s");
    const isFormula = /<f\b/.test(body);
    let text = "";
    let valueKind: XlsxCellKind = "empty";

    if (t === "inlineStr") {
      text = concatText(body);
      valueKind = "string";
    } else if (t === "s") {
      const idx = parseInt(innerV(body), 10);
      text = parsed.sharedStrings[idx] ?? "";
      valueKind = "string";
    } else if (t === "str") {
      text = decodeXml(innerV(body));
      valueKind = "string";
    } else if (t === "b") {
      text = innerV(body) === "1" ? "TRUE" : "FALSE";
      valueKind = "boolean";
    } else if (t === "e") {
      text = innerV(body);
      valueKind = "string";
    } else {
      const v = innerV(body);
      if (v === "") return { ref, text: "", kind: "empty", isFormula };
      const styleIndex = s !== undefined ? parseInt(s, 10) : -1;
      const n = Number(v);
      if (styleIndex >= 0 && parsed.styleIsDate[styleIndex]) {
        text = Number.isFinite(n) ? serialToIso(n, parsed.date1904) : v;
        valueKind = "date";
      } else if (styleIndex >= 0 && parsed.styleIsPercent[styleIndex] && Number.isFinite(n)) {
        text = formatPercent(n);
        valueKind = "number";
      } else if (styleIndex >= 0 && parsed.styleCurrency[styleIndex] && Number.isFinite(n)) {
        text = formatCurrency(n, parsed.styleCurrency[styleIndex]!);
        valueKind = "number";
      } else {
        text = v;
        valueKind = "number";
      }
    }
    return { ref, text, kind: isFormula ? "formula" : valueKind, isFormula };
  };

  const readSheet = (sheet: SheetRef): XlsxCell[][] => {
    const xml = pkg.readText(sheet.part) ?? "";
    const byRow = new Map<number, XlsxCell[]>();
    let maxCol = 0;
    for (const rm of xml.matchAll(/<row\b([^>]*)>([\s\S]*?)<\/row>/g)) {
      const rowNum = Number(attrVal(rm[1] ?? "", "r"));
      if (!Number.isFinite(rowNum) || rowNum < 1) continue;
      const cells: XlsxCell[] = [];
      for (const cm of (rm[2] ?? "").matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
        const attrs = cm[1] ?? "";
        const ref = attrVal(attrs, "r");
        if (!ref) continue;
        const pos = parseRef(ref);
        if (!pos) continue;
        cells[pos.col] = resolveCell(ref, attrs, cm[2] ?? "");
        if (pos.col + 1 > maxCol) maxCol = pos.col + 1;
      }
      byRow.set(rowNum, cells);
    }

    // Only rows that actually carry content, in order. This keeps columns reference-aligned while
    // ensuring leading/interior blank rows don't push the header off the top, and a stray far-down
    // cell can't balloon the grid into millions of empty rows.
    const rowNums = [...byRow.keys()]
      .filter((n) => (byRow.get(n) ?? []).some((c) => c !== undefined && c.kind !== "empty"))
      .sort((a, b) => a - b);

    return rowNums.map((rowNum) => {
      const src = byRow.get(rowNum) ?? [];
      const row: XlsxCell[] = [];
      for (let c = 0; c < maxCol; c++) {
        row.push(src[c] ?? { ref: `${indexToColumn(c)}${rowNum}`, text: "", kind: "empty", isFormula: false });
      }
      return row;
    });
  };

  return {
    sheets: () => parsed.sheets,
    resolveSheet: (selector) => {
      if (selector === undefined || selector === "") return parsed.sheets[0];
      const asString = String(selector).trim();
      const byName = parsed.sheets.find((sh) => sh.name === asString);
      if (byName) return byName;
      const asNum = Number(asString);
      if (Number.isInteger(asNum) && asNum >= 1 && asNum <= parsed.sheets.length) return parsed.sheets[asNum - 1];
      return undefined;
    },
    readSheet,
  };
}

export function openXlsxWorkbook(input: ArrayBuffer | Uint8Array): XlsxWorkbook {
  const pkg = openOfficePackage(input);
  const { sheets, date1904 } = parseWorkbook(pkg);
  const styles = parseStyles(pkg);
  const parsed: Parsed = {
    sheets,
    sharedStrings: parseSharedStrings(pkg),
    styleIsDate: styles.date,
    styleIsPercent: styles.percent,
    styleCurrency: styles.currency,
    date1904,
  };
  return makeWorkbook(pkg, parsed);
}
