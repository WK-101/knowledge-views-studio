import type { App } from "obsidian";

/** Open the note a row was aggregated from, honouring the user's link-open behaviour. */
export function openSourceNote(app: App, filePath: string, sourcePath = "", newLeaf = false): void {
  void app.workspace.openLinkText(filePath, sourcePath, newLeaf);
}
