import { describe, it, expect } from "vitest";
import { inlineFieldExtractor } from "../src/domain/extract/inline-field-extractor";
import { applyInlineFieldEdits } from "../src/services/write/inline-field-writer";
import type { Row } from "../src/domain/index";

const file = { filePath: "N.md", fileName: "N", folderPath: "", createdMs: 0, modifiedMs: 1, sizeBytes: 0 };
const extract = (content: string): Row[] => inlineFieldExtractor.extract({ file, content });
const doc = ["# Note", "status:: active", "Some prose with [priority:: high] inline.", "rating:: 4"].join("\n");

describe("inline-field extractor", () => {
  it("reads line-level and bracketed fields into one row", () => {
    const rows = extract(doc);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.cells).toMatchObject({ status: "active", priority: "high", rating: "4" });
  });
  it("yields no row when there are no inline fields", () => {
    expect(extract("Just prose, nothing structured.\n")).toHaveLength(0);
  });
});

describe("inline-field write-back", () => {
  const prov = () => extract(doc)[0]!.provenance;
  it("replaces a line-level value", () => {
    const r = applyInlineFieldEdits(doc, [{ provenance: prov(), column: "status", value: "done" }]);
    expect(r.applied).toBe(1);
    expect(r.content).toContain("status:: done");
    expect(extract(r.content)[0]!.cells.status).toBe("done");
  });
  it("replaces a bracketed value in place", () => {
    const r = applyInlineFieldEdits(doc, [{ provenance: prov(), column: "priority", value: "low" }]);
    expect(r.content).toContain("[priority:: low]");
    expect(r.content).toContain("Some prose with");
  });
  it("fails cleanly for a missing field", () => {
    const r = applyInlineFieldEdits(doc, [{ provenance: prov(), column: "nope", value: "x" }]);
    expect(r.applied).toBe(0);
    expect(r.failures).toHaveLength(1);
    expect(r.content).toBe(doc);
  });
});
