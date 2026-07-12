import { describe, it, expect } from "vitest";
import { embedViewComment, readEmbeddedView } from "../src/services/export/view-config";
import { createProfile } from "../src/services/index";

describe("embedded view config (export/import round-trip)", () => {
  it("restores columns, view, sort and layout while dropping identity/scope", () => {
    const profile = createProfile({
      name: "My reading list",
      columns: [{ name: "Título", type: "text", label: "Título 📖" }, { name: "Rating", type: "rating" }],
      view: { type: "cards", options: { image: "cover" } },
      sort: [{ field: "Rating", direction: "desc" }],
      rowHeight: "comfortable",
      tableWidth: "wide",
      frozenHeader: true,
    });

    const comment = embedViewComment(profile);
    expect(comment.startsWith("<!-- kvs:view ")).toBe(true);

    const restored = readEmbeddedView(`${comment}\n| Título | Rating |\n| --- | --- |\n| Dune | 5 |`);
    expect(restored).not.toBeNull();
    expect(restored!.columns).toEqual(profile.columns); // Unicode label survives base64
    expect(restored!.view).toEqual(profile.view);
    expect(restored!.sort).toEqual(profile.sort);
    expect(restored!.rowHeight).toBe("comfortable");
    expect(restored!.tableWidth).toBe("wide");
    expect(restored!.frozenHeader).toBe(true);
    // identity + scope are intentionally not carried
    expect("id" in restored!).toBe(false);
    expect("scope" in restored!).toBe(false);
    expect("name" in restored!).toBe(false);
  });

  it("returns null for files with no marker or a corrupt one", () => {
    expect(readEmbeddedView("| a | b |\n| --- | --- |\n| 1 | 2 |")).toBeNull();
    expect(readEmbeddedView("<!-- kvs:view @@@notbase64@@@ -->")).toBeNull();
  });
});
