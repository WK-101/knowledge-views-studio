import { describe, it, expect } from "vitest";
import { promotionPlan, identityCell, DEFAULT_WEB_PROMOTED_TEMPLATE } from "../src/services/notes/promotion-plan";
import { renderTemplate } from "../shared/template";

const input = (cells: Record<string, string>, patch: object = {}) => ({
  cells,
  columns: Object.keys(cells).map((name) => ({ name, type: /^note$/i.test(name) ? "link" : "text" })),
  matchKey: "source",
  ...patch,
});

describe("promotion · identity", () => {
  it("reads the identity from the column named after the key", () => {
    expect(identityCell({ source: "https://x/a" }, "source")).toBe("https://x/a");
  });

  it("accepts the aliases URLs actually live under", () => {
    // The frontmatter convention is `source`; the column is almost always called URL or Link.
    expect(identityCell({ URL: "https://x/a" }, "source")).toBe("https://x/a");
    expect(identityCell({ Link: "https://x/a" }, "source")).toBe("https://x/a");
  });

  it("prefers the exact key over an alias", () => {
    expect(identityCell({ source: "https://exact", URL: "https://alias" }, "source")).toBe("https://exact");
  });

  it("returns nothing rather than guessing when the row has no identity", () => {
    expect(identityCell({ Title: "No url here" }, "source")).toBe("");
  });

  it("works for other keys without the url aliases", () => {
    expect(identityCell({ DOI: "10.1/x", URL: "https://a" }, "doi")).toBe("10.1/x");
    expect(identityCell({ URL: "https://a" }, "doi")).toBe("");
  });
});

describe("promotion · planning", () => {
  it("names the note after the title", () => {
    const plan = promotionPlan(input({ Title: "A Good Read", URL: "https://x/a" }));
    expect(plan.fileBase).toBe("A Good Read");
  });

  it("sanitises a title a path can't hold", () => {
    expect(promotionPlan(input({ Title: 'A/B: "C"', URL: "https://x" })).fileBase).toBe("AB C");
  });

  it("falls back to any short cell rather than an unnamed file", () => {
    const plan = promotionPlan(input({ Description: "Short thing", URL: "https://x" }));
    expect(plan.fileBase).toBe("Short thing");
  });

  it("uses the configured folder, else the scope's Notes, else Notes", () => {
    expect(promotionPlan(input({ URL: "https://x" }, { configuredFolder: "Web" })).folder).toBe("Web");
    expect(promotionPlan(input({ URL: "https://x" }, { scopeFolder: "Reading" })).folder).toBe("Reading/Notes");
    expect(promotionPlan(input({ URL: "https://x" })).folder).toBe("Notes");
  });

  it("finds the column the wikilink should go back into", () => {
    const plan = promotionPlan(input({ Title: "T", URL: "https://x", Note: "" }));
    expect(plan.noteLinkColumn).toBe("Note");
  });

  it("says so when the view has no link column, rather than inventing one", () => {
    expect(promotionPlan(input({ Title: "T", URL: "https://x" })).noteLinkColumn).toBeNull();
  });
});

describe("promotion · variables", () => {
  it("makes every cell a variable under its own name", () => {
    // The cells are the vocabulary: a view with a Rating column can write {{rating}} without declaring it.
    const plan = promotionPlan(input({ Title: "T", Rating: "5", URL: "https://x/a" }));
    expect(plan.variables["rating"]).toBe("5");
    expect(plan.variables["title"]).toBe("T");
  });

  it("always provides source, date and annotations, so the default template renders", () => {
    const plan = promotionPlan(input({ Title: "T", URL: "https://x/a" }));
    expect(plan.variables["source"]).toBe("https://x/a");
    expect(plan.variables["date"]).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(plan.variables["annotations"]).toBe("");
  });

  it("renders the default web template into valid frontmatter and sections", () => {
    const plan = promotionPlan(input({ Title: "A study: part two", URL: "https://x/a" }));
    const note = renderTemplate(DEFAULT_WEB_PROMOTED_TEMPLATE, plan.variables);
    expect(note.startsWith("---\n")).toBe(true);
    expect(note).toContain('title: "A study: part two"');
    expect(note).toContain("source: https://x/a");
    expect(note).toContain("## Annotations");
  });
});
