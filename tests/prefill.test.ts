import { describe, it, expect } from "vitest";
import { prefillFor, canonicalFor } from "../extension/src/lib/prefill";

const view = (...names: string[]) => ({ columns: names.map((name) => ({ name, typeId: "text" })) }) as never;
const fields = [
  { key: "title", value: "A Read" },
  { key: "url", value: "https://x/a" },
  { key: "description", value: "About it." },
  { key: "author", value: "B. Writer" },
];

describe("prefill · matching by meaning", () => {
  it("fills a Link column from the page's url — the case that read as 'capture doesn't work'", () => {
    const out = prefillFor(view("Title", "Link"), fields);
    expect(out["Link"]).toBe("https://x/a");
    expect(out["Title"]).toBe("A Read");
  });

  it("fills Summary from description, Source from url, By from author", () => {
    const out = prefillFor(view("Summary", "Source", "By"), fields);
    expect(out["Summary"]).toBe("About it.");
    expect(out["Source"]).toBe("https://x/a");
    expect(out["By"]).toBe("B. Writer");
  });

  it("exact names win before aliases get a say", () => {
    const out = prefillFor(view("Description"), [
      { key: "description", value: "exact" },
    ]);
    expect(out["Description"]).toBe("exact");
  });

  it("spends each page field once — url doesn't fill both Link and Source", () => {
    const out = prefillFor(view("Link", "Source"), fields);
    const filled = [out["Link"], out["Source"]].filter((v) => v !== undefined);
    expect(filled).toHaveLength(1);
  });

  it("leaves unknown columns alone rather than guessing", () => {
    const out = prefillFor(view("Rating", "Status"), fields);
    expect(out).toEqual({});
  });

  it("skips empty page fields so a blank never overwrites the chance of a real match", () => {
    const out = prefillFor(view("Link"), [
      { key: "url", value: "  " },
      { key: "URL", value: "https://real/x" },
    ]);
    expect(out["Link"]).toBe("https://real/x");
  });

  it("knows the vocabulary both ways", () => {
    expect(canonicalFor("Keywords")).toBe("tags");
    expect(canonicalFor("Abstract")).toBe("description");
    expect(canonicalFor("Rating")).toBeNull();
  });
});
