import { describe, it, expect } from "vitest";
import { parsePairingInput, buildConnectionLink, isBridgePing, DISCOVERY_PORTS } from "../shared/protocol";
import { suggestCaptureTarget, dominantFile, hasUsableTarget } from "../src/services/capture/suggest-target";
import type { Row } from "../src/domain/index";

const row = (filePath: string): Row => ({ cells: {}, provenance: { filePath } }) as unknown as Row;

describe("setup · parsePairingInput", () => {
  it("accepts a bare code typed by hand", () => {
    expect(parsePairingInput("123456")).toEqual({ code: "123456" });
  });

  it("forgives the spacing a pasted code arrives with", () => {
    // Refusing "123 456" would be pedantry, not security: the code still has to be exactly right.
    expect(parsePairingInput("  123 456 ")).toEqual({ code: "123456" });
    expect(parsePairingInput("123-456")).toEqual({ code: "123456" });
  });

  it("reads a connection link carrying both port and code", () => {
    expect(parsePairingInput("kvs://pair?port=27180&code=123456")).toEqual({ code: "123456", port: 27180 });
  });

  it("reads a link whose parameters are the other way round", () => {
    expect(parsePairingInput("kvs://pair?code=123456&port=27181")).toEqual({ code: "123456", port: 27181 });
  });

  it("takes the code from a link with no port and discovers the port instead", () => {
    expect(parsePairingInput("kvs://pair?code=123456")).toEqual({ code: "123456" });
  });

  it("ignores a port outside the usable range rather than trying it", () => {
    expect(parsePairingInput("kvs://pair?port=80&code=123456")).toEqual({ code: "123456" });
    expect(parsePairingInput("kvs://pair?port=99999&code=123456")).toEqual({ code: "123456" });
  });

  it("rejects anything that isn't a code or a link", () => {
    for (const bad of ["", "   ", "hello", "kvs://pair?port=27180", "12", "abcdef"]) {
      expect(parsePairingInput(bad)).toBeNull();
    }
  });

  it("round-trips the link Obsidian offers", () => {
    const link = buildConnectionLink(27182, "654321");
    expect(parsePairingInput(link)).toEqual({ code: "654321", port: 27182 });
  });
});

describe("setup · isBridgePing", () => {
  it("recognises a bridge saying hello", () => {
    expect(isBridgePing({ kvs: true, protocol: 1 })).toBe(true);
  });

  it("rejects anything else answering on that port", () => {
    for (const bad of [null, undefined, 42, "ok", {}, { kvs: false, protocol: 1 }, { kvs: true }]) {
      expect(isBridgePing(bad)).toBe(false);
    }
  });

  it("offers a short list of ports to try, the default first", () => {
    expect(DISCOVERY_PORTS[0]).toBe(27180);
    expect(DISCOVERY_PORTS.length).toBeLessThanOrEqual(6);
  });
});

describe("setup · suggestCaptureTarget", () => {
  it("captures into the file the view already reads", () => {
    // Anywhere else would scatter one collection across two files.
    const target = suggestCaptureTarget([row("Reading/Books.md"), row("Reading/Books.md")], "Books");
    expect(target.notePath).toBe("Reading/Books.md");
    expect(target.createIfMissing).toBe(true);
  });

  it("follows the file most of the rows come from", () => {
    const rows = [row("A.md"), row("B.md"), row("B.md")];
    expect(suggestCaptureTarget(rows, "V").notePath).toBe("B.md");
  });

  it("names a new file after the view when there's nothing to follow", () => {
    expect(suggestCaptureTarget([], "Reading list").notePath).toBe("Captured/Reading list.md");
  });

  it("won't propose appending rows to a spreadsheet", () => {
    // The write path has different rules there, and a mistake is harder to see.
    expect(suggestCaptureTarget([row("Data/Sheet.xlsx")], "Sheet").notePath).toBe("Captured/Sheet.md");
  });

  it("strips characters a vault path can't hold", () => {
    expect(suggestCaptureTarget([], 'A/B:C*D?"E').notePath).toBe("Captured/ABCDE.md");
  });

  it("falls back to a usable name when the view's name leaves nothing", () => {
    expect(suggestCaptureTarget([], "///").notePath).toBe("Captured/Captured.md");
  });

  it("always proposes the row shape, since that's what a view is made of", () => {
    expect(suggestCaptureTarget([], "V").shape).toBe("row");
  });
});

describe("setup · dominantFile", () => {
  it("returns null when there are no rows to learn from", () => {
    expect(dominantFile([])).toBeNull();
  });

  it("ignores rows with no path", () => {
    expect(dominantFile([{ cells: {}, provenance: {} } as unknown as Row])).toBeNull();
  });

  it("keeps the first on a tie, matching the order sources were discovered", () => {
    expect(dominantFile([row("A.md"), row("B.md")])).toBe("A.md");
  });
});

describe("setup · hasUsableTarget", () => {
  it("accepts a configured row target", () => {
    expect(hasUsableTarget({ shape: "row", notePath: "A.md" }, undefined)).toBe(true);
  });

  it("accepts a note target without a path, since the folder is enough", () => {
    expect(hasUsableTarget({ shape: "note", folder: "Inbox" }, undefined)).toBe(true);
  });

  it("falls back to the older write-back setting", () => {
    expect(hasUsableTarget(undefined, "Library.md")).toBe(true);
  });

  it("reports a view that genuinely can't receive anything", () => {
    expect(hasUsableTarget(undefined, undefined)).toBe(false);
    expect(hasUsableTarget({ shape: "row", notePath: "  " }, "")).toBe(false);
  });
});
