import type { Row, RowProvenance, SourceExtractor, ExtractionInput } from "../../domain/index";
import { fnv1a } from "../../util/hash";
import { openXlsxWorkbook, type XlsxCell } from "./xlsx-workbook";

export const XLSX_EXTRACTOR_ID = "xlsx";

/**
 * Reads an `.xlsx` worksheet as rows: one KVS row per sheet row under a header row. Thin by
 * design — all OOXML/cell logic lives in {@link openXlsxWorkbook}. Read-only for this slice: it
 * records a locator + fingerprint (as the Markdown extractor does) so write-back can be added
 * later without re-extracting, but nothing here writes.
 */
export const xlsxExtractor: SourceExtractor = {
  id: XLSX_EXTRACTOR_ID,
  label: "Excel worksheet rows",
  extensions: ["xlsx"],
  extract({ file, bytes, options }: ExtractionInput): Row[] {
    if (!bytes) return []; // xlsx is binary-only
    const wb = openXlsxWorkbook(bytes);

    // Which sheet(s): a name/number selects one; "all" (or "*") combines every sheet, tagging each
    // row with a "Sheet" column so the origin stays clear and can be grouped/filtered on.
    const sel = (options?.sheet ?? "").trim();
    const combineAll = sel.toLowerCase() === "all" || sel === "*";
    const sheets = combineAll
      ? wb.sheets()
      : (() => {
          const one = wb.resolveSheet(sel === "" ? undefined : sel);
          return one ? [one] : [];
        })();
    if (sheets.length === 0) return [];

    const headerRow = Math.max(0, Number(options?.headerRow ?? "0") || 0); // 0-based index into content rows
    const rowNumberOf = (line: readonly XlsxCell[]): number => {
      for (const cell of line) {
        const m = /(\d+)$/.exec(cell.ref);
        if (m) return Number(m[1]);
      }
      return 0;
    };

    const rows: Row[] = [];
    for (const sheet of sheets) {
      const grid = wb.readSheet(sheet); // content rows only, columns reference-aligned
      const rawHeaders = grid[headerRow]?.map((c) => c.text.trim()) ?? [];
      if (rawHeaders.every((h) => h === "")) continue; // sheet has no usable header row

      // Disambiguate repeated headers ("Name", "Name (2)") so two same-named columns don't collapse.
      const counts = new Map<string, number>();
      const headers = rawHeaders.map((h) => {
        if (h === "") return "";
        const seen = counts.get(h) ?? 0;
        counts.set(h, seen + 1);
        return seen === 0 ? h : `${h} (${seen + 1})`;
      });

      for (let r = headerRow + 1; r < grid.length; r++) {
        const line = grid[r] ?? [];
        const cells: Record<string, string> = {};
        const formulaFields: string[] = [];
        headers.forEach((h, c) => {
          if (h === "") return;
          const cell = line[c];
          cells[h] = (cell?.text ?? "").trim();
          if (cell?.isFormula) formulaFields.push(h); // computed cell — protect from in-place edits
        });
        if (Object.values(cells).every((v) => v === "")) continue; // skip blank rows
        if (combineAll && !Object.keys(cells).some((k) => k.toLowerCase() === "sheet")) cells["Sheet"] = sheet.name;

        const provenance: RowProvenance = {
          filePath: file.filePath,
          extractor: XLSX_EXTRACTOR_ID,
          locator: { sheet: sheet.name, row: rowNumberOf(line), headerRow },
          fingerprint: fnv1a([sheet.name, ...line.map((c) => c?.text ?? "")].join("\u0000")),
          ...(formulaFields.length > 0 ? { readOnlyFields: formulaFields } : {}),
        };
        rows.push({ cells, file, provenance });
      }
    }
    return rows;
  },
};
