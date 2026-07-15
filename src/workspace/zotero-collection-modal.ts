import { FuzzySuggestModal, Notice, type App } from "obsidian";
import type { ZoteroCollection, ZoteroProvider } from "../services/zotero/provider";

/** A choice in the collection picker: the whole library, or a specific collection. */
interface CollectionChoice {
  readonly key: string | null; // null = whole library
  readonly name: string; // clean name, for the dashboard title
  readonly label: string; // indented display label with count
  readonly count: number;
}

/**
 * Lets the user pick a Zotero collection (or the whole library) to build a dashboard from. This surfaces
 * the collection scoping that already exists in the engine (`scope.zoteroCollectionKey`) — so a dashboard
 * can target "my Thesis collection" instead of the entire library. Collections are shown with an indent
 * that reflects their nesting, and their item counts, so the choice is legible.
 *
 * Fetching happens before the modal opens (via {@link openZoteroCollectionPicker}); if Zotero is
 * unreachable the picker isn't shown and the caller is told why.
 */
export class ZoteroCollectionModal extends FuzzySuggestModal<CollectionChoice> {
  constructor(
    app: App,
    private readonly choices: CollectionChoice[],
    private readonly onChoose: (key: string | null, name: string) => void,
  ) {
    super(app);
    this.setPlaceholder("Pick a Zotero collection to build a dashboard from…");
  }

  getItems(): CollectionChoice[] {
    return this.choices;
  }

  getItemText(choice: CollectionChoice): string {
    return choice.label;
  }

  onChooseItem(choice: CollectionChoice): void {
    this.onChoose(choice.key, choice.name);
  }
}

/**
 * Order collections as an indented tree (parents before children) and label each with its depth and item
 * count. Zotero's collection list is flat with parentKey references, so we build the hierarchy ourselves.
 */
export function buildCollectionChoices(collections: readonly ZoteroCollection[]): CollectionChoice[] {
  const byParent = new Map<string | null, ZoteroCollection[]>();
  for (const c of collections) {
    const list = byParent.get(c.parentKey) ?? [];
    list.push(c);
    byParent.set(c.parentKey, list);
  }
  for (const list of byParent.values()) list.sort((a, b) => a.name.localeCompare(b.name));

  const choices: CollectionChoice[] = [{ key: null, name: "Whole library", label: "Whole library", count: 0 }];
  const walk = (parentKey: string | null, depth: number): void => {
    for (const c of byParent.get(parentKey) ?? []) {
      choices.push({ key: c.key, name: c.name, label: `${"  ".repeat(depth)}${c.name} (${c.itemCount})`, count: c.itemCount });
      walk(c.key, depth + 1);
    }
  };
  walk(null, 0);
  return choices;
}

/**
 * Fetch collections and open the picker. On choice, calls `onPick` with the collection key (or null for the
 * whole library) and its clean name. Degrades with a notice — never a broken modal — when Zotero can't be
 * reached.
 */
export async function openZoteroCollectionPicker(app: App, provider: ZoteroProvider, onPick: (key: string | null, name: string) => void): Promise<void> {
  if (!(await provider.ping())) {
    new Notice("Can't reach Zotero. Make sure it's running with the local API enabled.");
    return;
  }
  const collections = await provider.listCollections();
  const choices = buildCollectionChoices(collections);
  new ZoteroCollectionModal(app, choices, onPick).open();
}
