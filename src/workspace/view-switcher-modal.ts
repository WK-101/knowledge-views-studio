import { FuzzySuggestModal, setIcon, type App, type FuzzyMatch } from "obsidian";
import type { Profile } from "../services/index";

/**
 * A quick-switcher for views. Scales past a flat menu: fuzzy search by name (and
 * group), grouped rendering, keyboard-navigable. Type-ahead over any number of views.
 */
export class ViewSwitcherModal extends FuzzySuggestModal<Profile> {
  constructor(
    app: App,
    private readonly profiles: readonly Profile[],
    private readonly activeId: string | null,
    private readonly iconForView: (type: string) => string,
    private readonly onChoose: (profile: Profile) => void,
  ) {
    super(app);
    this.setPlaceholder("Search views by name or category…");
    this.setInstructions([
      { command: "↑↓", purpose: "navigate" },
      { command: "↵", purpose: "open view" },
      { command: "esc", purpose: "dismiss" },
    ]);
  }

  getItems(): Profile[] {
    // Group together, groups alphabetical, ungrouped last; stable name order within.
    return [...this.profiles].sort((a, b) => {
      const ga = a.category ?? "\uffff";
      const gb = b.category ?? "\uffff";
      if (ga !== gb) return ga.localeCompare(gb);
      return a.name.localeCompare(b.name);
    });
  }

  getItemText(profile: Profile): string {
    return profile.category ? `${profile.category} ${profile.name}` : profile.name;
  }

  override renderSuggestion(match: FuzzyMatch<Profile>, el: HTMLElement): void {
    const profile = match.item;
    el.addClass("kvs-switch-item");
    const icon = el.createSpan({ cls: "kvs-switch-icon" });
    setIcon(icon, this.iconForView(profile.view.type));
    el.createSpan({ cls: "kvs-switch-name", text: profile.name });
    if (profile.category) el.createSpan({ cls: "kvs-switch-group", text: profile.category });
    if (profile.id === this.activeId) el.createSpan({ cls: "kvs-switch-current", text: "current" });
  }

  onChooseItem(profile: Profile): void {
    this.onChoose(profile);
  }
}
