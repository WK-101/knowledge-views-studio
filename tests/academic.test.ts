import { describe, it, expect } from "vitest";
import {
  splitAuthors,
  formatAuthorsShort,
  doiUrl,
  arxivUrl,
  pmidUrl,
  AUTHORS,
} from "../src/domain/columns/types/academic";

describe("academic kit helpers", () => {
  it("splits authors on ; / and / &", () => {
    expect(splitAuthors("Smith; Jones; Lee")).toEqual(["Smith", "Jones", "Lee"]);
    expect(splitAuthors("Smith and Jones")).toEqual(["Smith", "Jones"]);
    expect(splitAuthors("Smith & Jones")).toEqual(["Smith", "Jones"]);
  });

  it("formats a short citation form with surnames", () => {
    expect(formatAuthorsShort("Vaswani, A.")).toBe("Vaswani");
    expect(formatAuthorsShort("Smith; Jones")).toBe("Smith & Jones");
    expect(formatAuthorsShort("Devlin; Chang; Lee; Toutanova")).toBe("Devlin et al.");
    expect(formatAuthorsShort("Ashish Vaswani; Noam Shazeer")).toBe("Vaswani & Shazeer");
  });

  it("builds resolver URLs, normalising prefixes", () => {
    expect(doiUrl("10.1/x")).toBe("https://doi.org/10.1/x");
    expect(doiUrl("doi: 10.1/x")).toBe("https://doi.org/10.1/x");
    expect(doiUrl("https://doi.org/10.1/x")).toBe("https://doi.org/10.1/x");
    expect(arxivUrl("arXiv:1706.03762")).toBe("https://arxiv.org/abs/1706.03762");
    expect(pmidUrl("PMID: 12345")).toBe("https://pubmed.ncbi.nlm.nih.gov/12345/");
  });

  it("authors type compares by surnames and lists in plain text", () => {
    expect(AUTHORS.toPlainText("Smith; Jones")).toBe("Smith; Jones");
    expect(AUTHORS.isEmpty("")).toBe(true);
    expect(AUTHORS.isEmpty("Smith")).toBe(false);
  });
});
