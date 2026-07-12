import { describe, it, expect } from "vitest";
import { indexableExtensions } from "../src/workspace/search-extract";

describe("index scope — what search is allowed to read", () => {
  it("notes only, by default: no attachment is parsed until asked for", () => {
    const exts = indexableExtensions({ attachments: false, excel: false });
    expect([...exts]).toEqual(["md"]);
    expect(exts.has("pdf")).toBe(false);
  });

  it("attachments are read only when opted in", () => {
    const exts = indexableExtensions({ attachments: true, excel: false });
    for (const ext of ["md", "pdf", "docx", "pptx", "epub"]) expect(exts.has(ext)).toBe(true);
  });

  it("honours the Excel promise: .xlsx is ignored entirely while Excel sources are off", () => {
    // The Excel setting says Excel files are "ignored entirely" — search must not quietly read them.
    expect(indexableExtensions({ attachments: true, excel: false }).has("xlsx")).toBe(false);
    expect(indexableExtensions({ attachments: true, excel: true }).has("xlsx")).toBe(true);
  });

  it("Excel alone is not enough — attachments must also be opted into", () => {
    expect(indexableExtensions({ attachments: false, excel: true }).has("xlsx")).toBe(false);
  });
});
