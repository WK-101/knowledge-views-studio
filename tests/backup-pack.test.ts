import { describe, it, expect } from "vitest";
import {
  buildBackupPack,
  serializeBackupPack,
  parseBackupPack,
  packRowsToRows,
  KVS_PACK_EXTENSION,
} from "../src/services/backup-pack";
import { createProfile } from "../src/services/profile/profile";
import { getField, type Row } from "../src/domain/index";

const meta = (name: string) => ({
  filePath: `Notes/${name}.md`,
  fileName: name,
  folderPath: "Notes",
  createdMs: 1000,
  modifiedMs: 2000,
  sizeBytes: 42,
});
const row = (name: string, cells: Record<string, string>): Row => ({
  cells,
  file: meta(name),
  provenance: { filePath: `Notes/${name}.md`, extractor: "table", locator: { tableIndex: 0, rowIndex: 1 }, fingerprint: "x" },
});

describe("backup pack", () => {
  const profile = createProfile({
    name: "Reading list",
    scope: { mode: "folders", folders: ["Notes"], includeSubfolders: true },
    columns: [{ name: "Title", type: "text" }, { name: "Rating", type: "rating" }],
    sort: [{ field: "Rating", direction: "desc" }],
    tableWidth: "wide",
  });
  const columns = [
    { name: "Title", label: "Title", typeId: "text" },
    { name: "Rating", label: "Rating", typeId: "rating" },
  ];
  const rows = [row("Dune", { Title: "Dune", Rating: "5" }), row("It", { Title: "It", Rating: "3" })];

  it("captures profile, columns and all row data", () => {
    const pack = buildBackupPack(profile, columns, rows, "KVS test");
    expect(pack.rowCount).toBe(2);
    expect(pack.profile.name).toBe("Reading list");
    expect(pack.columns.map((c) => c.name)).toEqual(["Title", "Rating"]);
    expect(pack.rows[0]!.cells.Title).toBe("Dune");
    expect(pack.rows[0]!.file.fileName).toBe("Dune");
  });

  it("round-trips through serialize/parse with settings and data intact", () => {
    const text = serializeBackupPack(buildBackupPack(profile, columns, rows, "KVS test"));
    const pack = parseBackupPack(text);
    expect(pack).not.toBeNull();
    expect(pack!.profile.tableWidth).toBe("wide");
    expect(pack!.profile.sort[0]!.field).toBe("Rating");
    expect(pack!.rows).toHaveLength(2);
    expect(pack!.rows[1]!.cells.Title).toBe("It");
    expect(pack!.rows[1]!.file.filePath).toBe("Notes/It.md");
  });

  it("reconstructs Rows so getField (data + source fields) works", () => {
    const pack = parseBackupPack(serializeBackupPack(buildBackupPack(profile, columns, rows, "KVS test")))!;
    const restored = packRowsToRows(pack);
    expect(getField(restored[0]!, "Title")).toBe("Dune");
    expect(getField(restored[0]!, "note")).toBe("Dune"); // synthetic source field
    expect(getField(restored[0]!, "folder")).toBe("Notes");
  });

  it("rejects invalid content", () => {
    expect(parseBackupPack("")).toBeNull();
    expect(parseBackupPack("nope")).toBeNull();
    expect(parseBackupPack(JSON.stringify({ profile: {} }))).toBeNull(); // no rows/columns
  });

  it("uses the kvspack extension", () => {
    expect(KVS_PACK_EXTENSION).toBe("kvspack");
  });
});

import { restoreCellText, previewCellText, assetDataUrl, isImageMime, type PackAsset } from "../src/services/backup-pack";

describe("backup pack assets", () => {
  const assets: PackAsset[] = [
    { ref: "![[diagram.png]]", kind: "internal", name: "diagram.png", mime: "image/png", data: "AAAA" },
    { ref: "![](https://x.test/photo.jpg)", kind: "external", name: "photo.jpg", mime: "image/jpeg", data: "BBBB" },
    { ref: "![[report.pdf]]", kind: "internal", name: "report.pdf", mime: "application/pdf", data: "CCCC" },
  ];

  it("survives serialize/parse with bundled files intact", () => {
    const profile = createProfile({ name: "V", columns: [{ name: "Img", type: "image" }] });
    const cols = [{ name: "Img", label: "Img", typeId: "image" }];
    const pack = parseBackupPack(
      serializeBackupPack(buildBackupPack(profile, cols, [], "t", assets)),
    )!;
    expect(pack.assets).toHaveLength(3);
    expect(pack.assets[0]!.name).toBe("diagram.png");
    expect(pack.assets[1]!.kind).toBe("external");
    expect(pack.assets[2]!.mime).toBe("application/pdf");
  });

  it("restore rewrites external embeds to local files, leaves internal ones", () => {
    expect(restoreCellText("see ![[diagram.png]] and ![](https://x.test/photo.jpg)", assets)).toBe(
      "see ![[diagram.png]] and ![[photo.jpg]]",
    );
    expect(restoreCellText("![[report.pdf]]", assets)).toBe("![[report.pdf]]");
  });

  it("preview inlines images as data URLs (embeds and bare filenames)", () => {
    expect(previewCellText("![[diagram.png]]", assets)).toBe(`![](${assetDataUrl(assets[0]!)})`);
    expect(previewCellText("diagram.png", assets)).toBe(`![](${assetDataUrl(assets[0]!)})`); // bare image cell
    // non-image assets are not inlined
    expect(previewCellText("![[report.pdf]]", assets)).toBe("![[report.pdf]]");
  });

  it("classifies image mime types", () => {
    expect(isImageMime("image/png")).toBe(true);
    expect(isImageMime("application/pdf")).toBe(false);
  });
});
