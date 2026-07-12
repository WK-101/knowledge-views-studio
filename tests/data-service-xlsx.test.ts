import { describe, it, expect } from "vitest";
import { DataService } from "../src/services/data-service";
import { createProfile, DEFAULT_SETTINGS, type GlobalSettings } from "../src/services/profile/profile";
import { ExtractorRegistry, createDefaultColumnTypeRegistry } from "../src/domain/index";
import { xlsxExtractor, XLSX_EXTRACTOR_ID } from "../src/services/office/xlsx-extractor";
import { buildXlsx, buildExportTable } from "../src/services/export/export-format";
import { FakeVaultGateway } from "./_helpers";

const meta = { filePath: "x", fileName: "x", folderPath: "", createdMs: 0, modifiedMs: 0, sizeBytes: 0 };

function workbook(): Uint8Array {
  const table = buildExportTable(
    [
      { cells: { Task: "Write", Status: "Done" }, file: meta, provenance: { filePath: "x", extractor: "table", locator: {}, fingerprint: "" } },
      { cells: { Task: "Ship", Status: "Todo" }, file: meta, provenance: { filePath: "x", extractor: "table", locator: {}, fingerprint: "" } },
    ],
    [
      { name: "Task", label: "Task", typeId: "text" },
      { name: "Status", label: "Status", typeId: "text" },
    ],
    false,
  );
  return buildXlsx(table);
}

function setup(settings: Partial<GlobalSettings> = {}) {
  const gateway = new FakeVaultGateway();
  const extractors = new ExtractorRegistry().register(xlsxExtractor);
  const warnings: string[] = [];
  const service = new DataService({
    gateway,
    registry: createDefaultColumnTypeRegistry(),
    extractors,
    getSettings: () => ({ ...DEFAULT_SETTINGS, ...settings }),
    onSourceWarning: (path) => warnings.push(path),
  });
  return { gateway, service, warnings };
}

describe("DataService — xlsx source (opt-in)", () => {
  it("is completely inert when the feature is off", async () => {
    const { gateway, service } = setup({ enableExcelSources: false });
    gateway.setBinary("Data/book.xlsx", workbook());
    const profile = createProfile({ extractors: [XLSX_EXTRACTOR_ID] });
    const result = await service.query(profile);
    expect(result.rows).toHaveLength(0); // not even discovered
    service.dispose();
  });

  it("reads sheet rows as Rows when the feature is on", async () => {
    const { gateway, service } = setup({ enableExcelSources: true });
    gateway.setBinary("Data/book.xlsx", workbook());
    const profile = createProfile({ extractors: [XLSX_EXTRACTOR_ID] });
    const result = await service.query(profile);
    expect(result.rows).toHaveLength(2);
    expect(result.rows.map((r) => r.cells.Task)).toEqual(["Write", "Ship"]);
    expect(result.rows[0]!.cells.Status).toBe("Done");
    service.dispose();
  });

  it("respects folder scope for xlsx files", async () => {
    const { gateway, service } = setup({ enableExcelSources: true });
    gateway.setBinary("Data/book.xlsx", workbook());
    gateway.setBinary("Other/book.xlsx", workbook());
    const profile = createProfile({
      extractors: [XLSX_EXTRACTOR_ID],
      scope: { mode: "folders", folders: ["Data"], includeSubfolders: true },
    });
    const result = await service.query(profile);
    expect(result.rows).toHaveLength(2); // only Data/book.xlsx
    service.dispose();
  });

  it("re-extracts when the header-row option changes (cache signature includes options)", async () => {
    const { gateway, service } = setup({ enableExcelSources: true });
    gateway.setBinary("Data/book.xlsx", workbook());
    const base = createProfile({ extractors: [XLSX_EXTRACTOR_ID] });
    expect((await service.query(base)).rows).toHaveLength(2);

    // Treat row 2 (the first data row) as the header instead → one fewer data row, new columns.
    const shifted = createProfile({
      id: base.id,
      extractors: [XLSX_EXTRACTOR_ID],
      sourceOptions: { [XLSX_EXTRACTOR_ID]: { headerRow: "1" } },
    });
    const result = await service.query(shifted);
    expect(result.rows).toHaveLength(1);
    expect(Object.keys(result.rows[0]!.cells)).toContain("Write"); // "Write" is now a header
    service.dispose();
  });

  it("refreshes when the Excel toggle flips (setting is part of the cache key)", async () => {
    const gateway = new FakeVaultGateway();
    const extractors = new ExtractorRegistry().register(xlsxExtractor);
    const settings = { ...DEFAULT_SETTINGS, enableExcelSources: false };
    const service = new DataService({
      gateway,
      registry: createDefaultColumnTypeRegistry(),
      extractors,
      getSettings: () => settings,
    });
    gateway.setBinary("Data/book.xlsx", workbook());
    const profile = createProfile({ extractors: [XLSX_EXTRACTOR_ID] });

    expect((await service.query(profile)).rows).toHaveLength(0); // off
    settings.enableExcelSources = true;
    expect((await service.query(profile)).rows).toHaveLength(2); // on, without an app reload
    settings.enableExcelSources = false;
    expect((await service.query(profile)).rows).toHaveLength(0); // off again
    service.dispose();
  });

  it("skips a corrupt/mis-named .xlsx without breaking the view, and warns", async () => {
    const { gateway, service, warnings } = setup({ enableExcelSources: true });
    gateway.setBinary("Data/good.xlsx", workbook());
    gateway.setBinary("Data/bad.xlsx", new Uint8Array([1, 2, 3, 4, 5])); // not a zip
    const profile = createProfile({ extractors: [XLSX_EXTRACTOR_ID] });
    const result = await service.query(profile); // must not throw
    expect(result.rows).toHaveLength(2); // only the good workbook's rows
    expect(warnings).toContain("Data/bad.xlsx");
    service.dispose();
  });
});
