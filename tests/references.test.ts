import { describe, it, expect } from "vitest";
import { rowToReference, buildBibtex, buildBibliography, type ReferenceColumn } from "../src/services/export/references";

const cols: ReferenceColumn[] = [
  { name: "Cite key", typeId: "citekey" },
  { name: "Authors", typeId: "authors" },
  { name: "Year", typeId: "number" },
  { name: "Title", typeId: "text" },
  { name: "Venue", typeId: "text" },
  { name: "DOI", typeId: "doi" },
];
const row = {
  "Cite key": "vaswani2017",
  Authors: "Vaswani, A.; Shazeer, N.",
  Year: "2017",
  Title: "Attention Is All You Need",
  Venue: "NeurIPS",
  DOI: "10.5555/3295222",
};

describe("reference building", () => {
  it("maps a row to a reference by type + name", () => {
    const ref = rowToReference(cols, row);
    expect(ref.key).toBe("vaswani2017");
    expect(ref.authors).toEqual(["Vaswani, A.", "Shazeer, N."]);
    expect(ref.year).toBe("2017");
    expect(ref.doi).toBe("10.5555/3295222");
  });

  it("generates a key from surname + year when none is given", () => {
    const ref = rowToReference(cols, { ...row, "Cite key": "" });
    expect(ref.key).toBe("vaswani2017");
  });

  it("builds a valid BibTeX entry", () => {
    const bib = buildBibtex([rowToReference(cols, row)]);
    expect(bib).toContain("@article{vaswani2017,");
    expect(bib).toContain("author = {Vaswani, A. and Shazeer, N.}");
    expect(bib).toContain("title = {Attention Is All You Need}");
    expect(bib).toContain("doi = {10.5555/3295222}");
  });

  it("formats APA and MLA bibliographies", () => {
    const ref = rowToReference(cols, row);
    const apa = buildBibliography([ref], "apa");
    expect(apa).toContain("(2017).");
    expect(apa).toContain("Vaswani, A.");
    const mla = buildBibliography([ref], "mla");
    expect(mla).toContain('"Attention Is All You Need."');
    expect(mla).toContain("et al");
  });
});
