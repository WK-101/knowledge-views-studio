import type { Row, RowProvenance } from "../model";
import type { ExtractionInput, SourceExtractor } from "./extractor";
import { parseMarkdownTables } from "./markdown-table";
import { fnv1a } from "../../util/hash";

export const TABLE_EXTRACTOR_ID = "table";

/** Stable fingerprint of a row's cells; shared with the write-back relocator. */
export function fingerprintCells(cells: readonly string[]): string {
  return fnv1a(cells.map((c) => c.trim()).join("\u0001"));
}

/**
 * The flagship extractor: one row per in-body Markdown table row. Each row
 * carries provenance (file + table index + row index + line) and a content
 * fingerprint, which together let later phases write an edit back to the exact
 * source cell — the capability no comparable plugin offers.
 */
export const tableExtractor: SourceExtractor = {
  id: TABLE_EXTRACTOR_ID,
  label: "In-body table rows",
  extract({ file, content }: ExtractionInput): Row[] {
    const tables = parseMarkdownTables(content);
    const rows: Row[] = [];

    tables.forEach((table, tableIndex) => {
      table.rows.forEach((row, rowIndex) => {
        const cells: Record<string, string> = {};
        table.headers.forEach((header, columnIndex) => {
          const name = header.trim();
          if (name === "") return;
          cells[name] = (row.cells[columnIndex] ?? "").trim();
        });

        const provenance: RowProvenance = {
          filePath: file.filePath,
          extractor: TABLE_EXTRACTOR_ID,
          locator: { tableIndex, rowIndex, line: row.line },
          fingerprint: fingerprintCells(row.cells),
        };

        rows.push({ cells, file, provenance });
      });
    });

    return rows;
  },
};
