import { describe, it, expect } from "vitest";
import { WriterService } from "../src/services/write/writer-service";
import { FakeVaultGateway } from "./_helpers";
import { buildXlsx, DEFAULT_XLSX_OPTIONS } from "../src/services/export/export-format";
import { openXlsxWorkbook } from "../src/services/office/xlsx-workbook";
import { XLSX_EXTRACTOR_ID } from "../src/services/office/xlsx-extractor";
import type { RowProvenance } from "../src/domain/index";

const OPTS = { ...DEFAULT_XLSX_OPTIONS, sheetName: "Export" };

function xlsxBytes(): Uint8Array {
  return buildXlsx({ headers: ["Task", "Status"], rows: [["A", "Todo"], ["B", "Doing"]] }, OPTS);
}

function prov(path: string, row: number): RowProvenance {
  return { filePath: path, extractor: XLSX_EXTRACTOR_ID, locator: { sheet: "Export", row, headerRow: 0 }, fingerprint: "x" };
}

const backupKey = (path: string): string => {
  const now = new Date();
  const p2 = (n: number) => (n < 10 ? `0${n}` : String(n));
  const day = `${now.getFullYear()}-${p2(now.getMonth() + 1)}-${p2(now.getDate())}`;
  return `_kvs-backups/${path.replace(/[\\/]/g, "__")}.${day}.xlsx`;
};

describe("Excel backup before write", () => {
  it("creates a same-day backup before the first edit, then writes the edit", async () => {
    const gateway = new FakeVaultGateway();
    gateway.setBinary("data/sheet.xlsx", xlsxBytes());
    const writer = new WriterService(gateway, { excelBackup: () => true });

    const result = await writer.editCells([{ provenance: prov("data/sheet.xlsx", 2), column: "Status", value: "Done" }]);
    expect(result.applied).toBe(1);

    // Backup exists and holds the ORIGINAL (pre-edit) value.
    const backup = await gateway.readBinary(backupKey("data/sheet.xlsx"));
    expect(backup.byteLength).toBeGreaterThan(0);
    const wb = openXlsxWorkbook(new Uint8Array(backup));
    const grid = wb.readSheet(wb.resolveSheet("Export")!).map((r) => r.map((c) => c.text));
    expect(grid[1]).toEqual(["A", "Todo"]); // original, not "Done"
  });

  it("does not create a second backup for another edit the same day", async () => {
    const gateway = new FakeVaultGateway();
    gateway.setBinary("s.xlsx", xlsxBytes());
    const writer = new WriterService(gateway, { excelBackup: () => true });
    await writer.editCells([{ provenance: prov("s.xlsx", 2), column: "Status", value: "One" }]);
    const firstBackup = new Uint8Array(await gateway.readBinary(backupKey("s.xlsx")));
    await writer.editCells([{ provenance: prov("s.xlsx", 3), column: "Status", value: "Two" }]);
    const secondBackup = new Uint8Array(await gateway.readBinary(backupKey("s.xlsx")));
    // Backup unchanged — still the day's original, not overwritten by the interim state.
    expect(secondBackup).toEqual(firstBackup);
  });

  it("skips backup when disabled", async () => {
    const gateway = new FakeVaultGateway();
    gateway.setBinary("s.xlsx", xlsxBytes());
    const writer = new WriterService(gateway, { excelBackup: () => false });
    await writer.editCells([{ provenance: prov("s.xlsx", 2), column: "Status", value: "X" }]);
    expect(await gateway.exists(backupKey("s.xlsx"))).toBe(false);
  });
});
