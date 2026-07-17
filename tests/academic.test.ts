import { describe, it, expect } from "vitest";
import {
  splitAuthors,
  formatAuthorsShort,
  doiUrl,
  doiPrefix,
  doiRegistrant,
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

describe("DOI prefix and publisher lookup (offline)", () => {
  it("extracts the registrant prefix, tolerating resolver/doi: forms", () => {
    expect(doiPrefix("10.1145/3292500.3330701")).toBe("10.1145");
    expect(doiPrefix("doi: 10.1038/s41586-021-03819-2")).toBe("10.1038");
    expect(doiPrefix("https://doi.org/10.1109/5.771073")).toBe("10.1109");
    expect(doiPrefix("not-a-doi")).toBeNull();
  });

  it("maps common prefixes to publishers", () => {
    expect(doiRegistrant("10.1145/3292500")).toBe("ACM");
    expect(doiRegistrant("10.1038/nature12373")).toBe("Nature");
    expect(doiRegistrant("10.18653/v1/2020.acl-main.1")).toBe("ACL");
    expect(doiRegistrant("10.1109/5.771073")).toBe("IEEE");
  });

  it("returns null for a well-formed DOI whose prefix isn't in the map (long tail)", () => {
    expect(doiRegistrant("10.9999/unknown.registrant")).toBeNull();
    // …and null for non-DOIs, so callers can fall back to the prefix or generic label.
    expect(doiRegistrant("arXiv:1706.03762")).toBeNull();
  });
});
