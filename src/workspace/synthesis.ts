import { Notice, TFile, type App } from "obsidian";
import { ANNOTATIONS_START, buildThemeSynthesis, parseAnnotationRegion, type NoteHighlights } from "../services/index";

/**
 * Scan the vault for notes with an ingested annotations region, group every highlight by theme, and
 * write a transclusion-based "Highlight synthesis" note.
 */
export async function buildHighlightSynthesis(app: App): Promise<void> {
  const notice = new Notice("Collecting highlights…", 0);
  try {
    const notes: NoteHighlights[] = [];
    for (const file of app.vault.getMarkdownFiles()) {
      if (file.basename === "Highlight synthesis") continue;
      const content = await app.vault.cachedRead(file);
      if (!content.includes(ANNOTATIONS_START)) continue;
      const highlights = parseAnnotationRegion(content).filter((h) => h.text.trim() !== "" || h.comment.trim() !== "");
      if (highlights.length > 0) notes.push({ note: file.basename, highlights });
    }
    notice.hide();
    if (notes.length === 0) {
      new Notice("No ingested highlights found. Sync annotations on a paper note first.");
      return;
    }
    const doc = buildThemeSynthesis(notes);
    const path = "Highlight synthesis.md";
    const existing = app.vault.getAbstractFileByPath(path);
    if (existing instanceof TFile) await app.vault.modify(existing, doc);
    else await app.vault.create(path, doc);
    const target = app.vault.getAbstractFileByPath(path);
    if (target instanceof TFile) await app.workspace.getLeaf(true).openFile(target);
    const total = notes.reduce((n, x) => n + x.highlights.length, 0);
    new Notice(`Synthesised ${total} highlight(s) from ${notes.length} note(s).`);
  } catch (error) {
    notice.hide();
    console.error("[KVS] synthesis failed:", error);
    new Notice(`Couldn't build synthesis: ${error instanceof Error ? error.message : "unexpected error"}`);
  }
}
