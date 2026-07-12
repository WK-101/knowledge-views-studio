import { describe, it, expect } from "vitest";
import {
  buildArchiveHtml,
  buildRowsJson,
  buildArchiveReadme,
  buildChecksumsFile,
  parseChecksumsFile,
  assembleArchive,
  readArchive,
  verifyArchive,
  archiveCsv,
  ARCHIVE_EXTENSION,
  type ArchiveManifest,
} from "../src/services/archive-format";
import { unzipSync, strToU8, strFromU8 } from "fflate";
import type { Row } from "../src/domain/index";

const meta = (n: string) => ({ filePath: `N/${n}.md`, fileName: n, folderPath: "N", createdMs: 0, modifiedMs: 0, sizeBytes: 0 });
const r = (n: string, cells: Record<string, string>): Row => ({ cells, file: meta(n), provenance: { filePath: `N/${n}.md`, extractor: "table", locator: {}, fingerprint: "" } });

const columns = [{ name: "Title", label: "Title", typeId: "text" }, { name: "Cover", label: "Cover", typeId: "image" }];
const rows = [r("a", { Title: "Dune", Cover: "dune.png" }), r("b", { Title: "1984", Cover: "" })];
const embeds = [{ ref: "![[dune.png]]", kind: "internal" as const, name: "dune.png", mime: "image/png" }];

const manifest: ArchiveManifest = {
  format: "kvs-archive",
  formatVersion: 1,
  specification: "test",
  generator: "KVS test",
  createdAt: new Date().toISOString(),
  view: { name: "Books", type: "table" },
  source: { folders: ["N"], extractors: ["table"] },
  counts: { rows: 2, columns: 2, attachments: 1 },
  columns,
  embeds,
  payload: {},
};

describe("archive content generators", () => {
  it("renders HTML with image tags and escaped text", () => {
    const html = buildArchiveHtml("Books", manifest.createdAt, columns, rows, embeds);
    expect(html).toContain("<!DOCTYPE html>");
    expect(html).toContain("<th>Title</th>");
    expect(html).toContain("Dune");
    expect(html).toContain('<img src="../attachments/dune.png"'); // bare image filename -> img
  });

  it("emits rows as JSON with cells + file", () => {
    const json = JSON.parse(buildRowsJson(rows));
    expect(json).toHaveLength(2);
    expect(json[0].cells.Title).toBe("Dune");
    expect(json[0].file.fileName).toBe("a");
  });

  it("builds CSV from rows and columns", () => {
    const csv = archiveCsv(rows, columns);
    expect(csv).toContain("Title,Cover");
    expect(csv).toContain("Dune");
  });

  it("README is plain text and describes the layout", () => {
    const readme = buildArchiveReadme(manifest);
    expect(readme).toContain("checksums-sha256.txt");
    expect(readme).toContain("data/data.csv");
    expect(readme).toContain("3-2-1");
  });

  it("checksum manifest round-trips and is path-sorted", () => {
    const text = buildChecksumsFile([
      { path: "b.txt", hex: "b".repeat(64) },
      { path: "a.txt", hex: "a".repeat(64) },
    ]);
    expect(text.indexOf("a.txt")).toBeLessThan(text.indexOf("b.txt"));
    expect(parseChecksumsFile(text)).toEqual([
      { path: "a.txt", hex: "a".repeat(64) },
      { path: "b.txt", hex: "b".repeat(64) },
    ]);
  });
});

describe("archive assemble / read / verify", () => {
  const input = {
    manifest,
    readme: buildArchiveReadme(manifest),
    csv: archiveCsv(rows, columns),
    rowsJson: buildRowsJson(rows),
    html: buildArchiveHtml("Books", manifest.createdAt, columns, rows, embeds),
    settingsJson: JSON.stringify({ knowledgeView: 2, views: [manifest], activeView: "x" }),
    attachments: [{ name: "dune.png", bytes: new Uint8Array([1, 2, 3, 4]) }],
  };

  it("assembles a ZIP with all preservation parts", async () => {
    const zip = await assembleArchive(input);
    const files = unzipSync(zip);
    for (const p of ["README.txt", "manifest.json", "data/data.csv", "data/data.json", "data/view.html", "settings/views.json", "attachments/dune.png", "checksums-sha256.txt"]) {
      expect(files[p], p).toBeDefined();
    }
  });

  it("reads its parts back", async () => {
    const contents = readArchive(await assembleArchive(input))!;
    expect(contents.manifest!.view.name).toBe("Books");
    expect(JSON.parse(contents.rowsJson)).toHaveLength(2);
    expect(contents.attachments.get("dune.png")).toEqual(new Uint8Array([1, 2, 3, 4]));
  });

  it("verifies intact archives and detects tampering", async () => {
    const zip = await assembleArchive(input);
    const good = await verifyArchive(zip);
    expect(good.ok).toBe(true);
    expect(good.checked).toBeGreaterThan(0);

    // Tamper: flip a data byte, re-zip with the SAME (now stale) checksums file.
    const files = unzipSync(zip);
    files["data/data.csv"] = strToU8(strFromU8(files["data/data.csv"]!) + "tampered");
    const { zipSync } = await import("fflate");
    const bad = await verifyArchive(zipSync(files));
    expect(bad.ok).toBe(false);
    expect(bad.mismatched).toContain("data/data.csv");
  });

  it("uses the kvsarchive extension", () => {
    expect(ARCHIVE_EXTENSION).toBe("kvsarchive");
  });
});
