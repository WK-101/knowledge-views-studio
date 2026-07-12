import { describe, it, expect } from "vitest";
import { fileInScope, filterPathsByScope, type ScopeConfig } from "../src/domain/scope";

describe("fileInScope", () => {
  it("includes everything in vault mode", () => {
    const scope: ScopeConfig = { mode: "vault", folders: [], includeSubfolders: false };
    expect(fileInScope("a/b/c.md", scope)).toBe(true);
  });

  it("respects folder lists with subfolders", () => {
    const scope: ScopeConfig = {
      mode: "folders",
      folders: ["Research"],
      includeSubfolders: true,
    };
    expect(fileInScope("Research/x.md", scope)).toBe(true);
    expect(fileInScope("Research/sub/y.md", scope)).toBe(true);
    expect(fileInScope("Other/z.md", scope)).toBe(false);
  });

  it("matches only the immediate folder when subfolders are excluded", () => {
    const scope: ScopeConfig = {
      mode: "folders",
      folders: ["/Research/"],
      includeSubfolders: false,
    };
    expect(fileInScope("Research/x.md", scope)).toBe(true);
    expect(fileInScope("Research/sub/y.md", scope)).toBe(false);
  });

  it("filterPathsByScope filters a list", () => {
    const scope: ScopeConfig = { mode: "folders", folders: ["A"], includeSubfolders: true };
    expect(filterPathsByScope(["A/1.md", "B/2.md", "A/x/3.md"], scope)).toEqual([
      "A/1.md",
      "A/x/3.md",
    ]);
  });
});
