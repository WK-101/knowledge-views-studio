import { describe, it, expect } from "vitest";
import { applyDevicePolicy, indexableExtensions, type IndexScope } from "../src/workspace/search-extract";

describe("index scope — what search is allowed to read", () => {
  it("notes only, by default: no attachment is parsed until asked for", () => {
    const exts = indexableExtensions({ attachments: false, excel: false });
    expect([...exts]).toEqual(["md"]);
    expect(exts.has("pdf")).toBe(false);
  });

  it("attachments are read only when opted in", () => {
    const exts = indexableExtensions({ attachments: true, excel: false });
    for (const ext of ["md", "pdf", "docx", "pptx", "epub"]) expect(exts.has(ext)).toBe(true);
  });

  it("honours the Excel promise: .xlsx is ignored entirely while Excel sources are off", () => {
    // The Excel setting says Excel files are "ignored entirely" — search must not quietly read them.
    expect(indexableExtensions({ attachments: true, excel: false }).has("xlsx")).toBe(false);
    expect(indexableExtensions({ attachments: true, excel: true }).has("xlsx")).toBe(true);
  });

  it("Excel alone is not enough — attachments must also be opted into", () => {
    expect(indexableExtensions({ attachments: false, excel: true }).has("xlsx")).toBe(false);
  });
});

describe("device policy — what a phone is allowed to do", () => {
  const phone = { mobile: true, phone: true };
  const tablet = { mobile: true, phone: false };
  const desktop = { mobile: false, phone: false };

  const scope = (over: Partial<IndexScope> = {}): IndexScope => ({ attachments: false, excel: false, ...over });

  it("leaves a desktop entirely alone", () => {
    const s = scope({ attachments: true, semanticEngine: "neural", excel: true });
    expect(applyDevicePolicy(s, desktop)).toEqual(s);
  });

  it("does not let a desktop's choice to index attachments conscript a phone", () => {
    // The scenario that matters: the user turned attachments on at their desk. data.json syncs. The
    // phone must not silently inherit "run pdf.js over every book in the vault".
    const synced = scope({ attachments: true });
    expect(applyDevicePolicy(synced, phone).attachments).toBe(false);
  });

  it("indexes attachments on mobile only when asked for mobile, specifically", () => {
    const asked = scope({ attachments: true, attachmentsOnMobile: true });
    expect(applyDevicePolicy(asked, phone).attachments).toBe(true);
  });

  it("will not index attachments on mobile just because mobile was ticked — the feature must be on at all", () => {
    const contradiction = scope({ attachments: false, attachmentsOnMobile: true });
    expect(applyDevicePolicy(contradiction, phone).attachments).toBe(false);
  });

  it("never runs the neural engine on mobile, and falls back rather than failing", () => {
    const neural = scope({ semanticEngine: "neural" });
    // Not "off" — "builtin". Semantic search still works; it just uses the engine a phone can carry.
    expect(applyDevicePolicy(neural, phone).semanticEngine).toBe("builtin");
  });

  it("treats a tablet as mobile: it has the same battery and the same webview", () => {
    const s = scope({ attachments: true, semanticEngine: "neural" });
    expect(applyDevicePolicy(s, tablet)).toMatchObject({ attachments: false, semanticEngine: "builtin" });
  });

  it("still indexes notes on mobile — they are cheap, and they are the point", () => {
    const restricted = applyDevicePolicy(scope({ attachments: true }), phone);
    expect(indexableExtensions(restricted).has("md")).toBe(true);
    expect(indexableExtensions(restricted).has("pdf")).toBe(false);
  });

  it("leaves every other choice untouched — it is a veto, not a rewrite", () => {
    const s = scope({ excel: true, relevance: { semanticWeight: 0.9 } as never });
    const out = applyDevicePolicy(s, phone);
    expect(out.excel).toBe(true);
    expect(out.relevance).toBe(s.relevance);
  });
});
