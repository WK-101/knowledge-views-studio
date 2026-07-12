import { AbstractInputSuggest, type App } from "obsidian";

/** Autocomplete for a tags input, sourced from all tags known to the vault. */
export class TagSuggest extends AbstractInputSuggest<string> {
  constructor(
    app: App,
    private readonly inputEl: HTMLInputElement,
    private readonly existing: () => readonly string[],
    private readonly onChoose: (tag: string) => void,
  ) {
    super(app, inputEl);
  }

  private allTags(): string[] {
    const tags =
      (this.app.metadataCache as unknown as { getTags?: () => Record<string, number> }).getTags?.() ?? {};
    return Object.keys(tags).map((t) => t.replace(/^#/, ""));
  }

  protected getSuggestions(query: string): string[] {
    const q = query.replace(/^#/, "").toLowerCase();
    const chosen = new Set(this.existing().map((t) => t.toLowerCase()));
    return this.allTags()
      .filter((t) => !chosen.has(t.toLowerCase()) && t.toLowerCase().includes(q))
      .sort((a, b) => a.localeCompare(b))
      .slice(0, 50);
  }

  override renderSuggestion(value: string, el: HTMLElement): void {
    el.addClass("kvs-tag-suggestion");
    el.createSpan({ cls: "tag", text: `#${value}` });
  }

  override selectSuggestion(value: string): void {
    this.onChoose(value);
    this.inputEl.value = "";
    this.close();
  }
}
