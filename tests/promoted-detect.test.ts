import { describe, it, expect } from "vitest";
import { noteLinkColumnName, wikilinkTarget, citeKeyColumnName } from "../src/views/promoted-detect";

// Mirrors the Paper Library's configured columns (note the relation "Cites" must NOT win).
const libraryCols = [
  { name: "Cite key", type: "citekey" },
  { name: "Tags", type: "tags" },
  { name: "DOI", type: "doi" },
  { name: "Cites", type: "relation" },
  { name: "Note", type: "link" },
];

describe("promoted-note detection", () => {
  it("finds the Note column by name (not the relation Cites column)", () => {
    expect(noteLinkColumnName(libraryCols)).toBe("Note");
  });

  it("falls back to a link-typed column when none is named Note", () => {
    expect(noteLinkColumnName([{ name: "Paper file", type: "link" }, { name: "X", type: "text" }])).toBe("Paper file");
  });

  it("finds a Note column even if its type isn't link (auto-column views)", () => {
    expect(noteLinkColumnName([{ name: "Note", type: "text" }])).toBe("Note");
  });

  it("returns null when there's no note/link column", () => {
    expect(noteLinkColumnName([{ name: "Title", type: "text" }])).toBeNull();
  });

  it("extracts the wikilink target", () => {
    expect(wikilinkTarget("[[vaswani2017]]")).toBe("vaswani2017");
    expect(wikilinkTarget("[[Papers/vaswani2017|Attention]]")).toBe("Papers/vaswani2017");
    expect(wikilinkTarget("")).toBeNull();
    expect(wikilinkTarget("no link here")).toBeNull();
  });

  it("finds the cite-key column by type or name", () => {
    expect(citeKeyColumnName(libraryCols)).toBe("Cite key");
    expect(citeKeyColumnName([{ name: "Key", type: "citekey" }])).toBe("Key");
    expect(citeKeyColumnName([{ name: "X", type: "text" }])).toBeNull();
  });
});
