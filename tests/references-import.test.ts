import { describe, it, expect } from "vitest";
import { parseBibtex, parseReferences, referencesToNote } from "../src/services/import/references-import";

describe("reference import", () => {
  it("parses BibTeX entries with braces and 'and'-separated authors", () => {
    const bib = `@article{smith2020,
  author = {Smith, John and Jones, Kate},
  title = {A Study of {Things}},
  journal = {Nature},
  year = {2020},
  doi = {10.1/x}
}

@inproceedings{lee2019, author={Lee, M.}, title={Fast Methods}, booktitle={ICML}, year={2019}}`;
    const refs = parseBibtex(bib);
    expect(refs).toHaveLength(2);
    expect(refs[0]).toMatchObject({ citeKey: "smith2020", authors: "Smith, John; Jones, Kate", year: "2020", title: "A Study of Things", venue: "Nature", doi: "10.1/x" });
    expect(refs[1]).toMatchObject({ citeKey: "lee2019", venue: "ICML", itemType: "inproceedings" });
  });

  it("parses a Zotero-style CSV export by column name", () => {
    const csv = `Key,Item Type,Author,Title,Publication Year,Publication Title,DOI\nABCD,journalArticle,"Smith, John; Jones, Kate",Great Paper,2021,Science,10.2/y`;
    const refs = parseReferences(csv);
    expect(refs).toHaveLength(1);
    expect(refs[0]).toMatchObject({ citeKey: "ABCD", authors: "Smith, John; Jones, Kate", year: "2021", title: "Great Paper", venue: "Science", doi: "10.2/y" });
  });

  it("handles quoted CSV fields containing commas", () => {
    const csv = `Key,Title,Author\nk1,"Methods, Models and More","Doe, Jane"`;
    const refs = parseReferences(csv);
    expect(refs[0]!.title).toBe("Methods, Models and More");
  });

  it("parses BibTeX abstract and keywords into abstract + hashtags", () => {
    const bib = `@article{k, author={A}, title={T}, year={2020}, abstract={A short abstract.}, keywords={machine learning, transformers}}`;
    const ref = parseBibtex(bib)[0]!;
    expect(ref.abstract).toBe("A short abstract.");
    expect(ref.tags).toBe("#machine-learning #transformers");
  });

  it("parses Zotero CSV manual/automatic tags into hashtags", () => {
    const csv = `Key,Title,Manual Tags,Automatic Tags,Abstract Note\nk,T,"deep learning; vision",nlp,"An abstract."`;
    const ref = parseReferences(csv)[0]!;
    expect(ref.tags).toBe("#deep-learning #vision #nlp");
    expect(ref.abstract).toBe("An abstract.");
  });

  it("renders imported references as a Markdown papers table", () => {
    const note = referencesToNote([
      { citeKey: "a", authors: "X", year: "2020", title: "T", venue: "V", doi: "10.1/z", itemType: "article", abstract: "Sum.", tags: "#ml" },
    ]);
    expect(note).toContain("| Cite key | Authors | Year | Title | Venue | Tags | Summary | DOI |");
    expect(note).toContain("| a | X | 2020 | T | V | #ml | Sum. | 10.1/z |");
  });
});
