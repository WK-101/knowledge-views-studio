import { Notice, SuggestModal, type App } from "obsidian";
import { resolveColumns } from "../views/view-model";
import type { ProfileStore } from "../services/profile/profile-store";
import type { Profile } from "../services/profile/profile";
import type { DataService } from "../services/data-service";
import { CaptureService, findDuplicate, prepareCapture } from "../services/capture/capture-service";
import { parseCaptureText, effectiveTarget } from "../services/capture/parse";
import type { CaptureColumn } from "../services/capture/types";
import type { Row } from "../domain/index";

/**
 * Capture from inside Obsidian.
 *
 * This exists partly for its own sake — pasting a URL or a few lines into a view is useful on its own — and
 * partly to keep the capture pipeline honest. It drives exactly the path the browser companion will drive
 * later, so the mapping, duplicate checks and writing are all exercised before any of it is exposed over a
 * network, and any rough edge shows up here first.
 */

/**
 * The columns capture should map onto: what the view actually renders, plus the default values only the
 * configuration knows about. Resolved columns carry the resolved type and the option list; the configured
 * ones carry `defaultValue`. Capture wants both, so they're merged by name here rather than either shape
 * being bent to fit.
 */
export function captureColumnsFor(profile: Profile, rows: readonly Row[]): CaptureColumn[] {
  const configured = new Map(profile.columns.map((c) => [c.name.toLowerCase(), c]));
  return resolveColumns(profile, rows).map((c) => {
    const config = configured.get(c.name.toLowerCase());
    return {
      name: c.name,
      typeId: c.typeId,
      role: c.role,
      ...(c.options ? { options: c.options } : {}),
      ...(config?.defaultValue ? { defaultValue: config.defaultValue } : {}),
    };
  });
}

class ViewPicker extends SuggestModal<Profile> {
  constructor(
    app: App,
    private readonly profiles: readonly Profile[],
    private readonly onPick: (profile: Profile) => void,
  ) {
    super(app);
    this.setPlaceholder("Capture into which view?");
  }
  getSuggestions(query: string): Profile[] {
    const q = query.toLowerCase();
    return this.profiles.filter((p) => p.name.toLowerCase().includes(q));
  }
  renderSuggestion(profile: Profile, el: HTMLElement): void {
    el.createDiv({ text: profile.name });
    const target = effectiveTarget(profile);
    const where =
      target === null
        ? "No capture target set"
        : target.shape === "note"
          ? `New note in ${target.folder === undefined || target.folder === "" ? "vault root" : target.folder}`
          : target.destination === "periodic"
            ? `Row in your ${target.periodic?.period ?? "daily"} note`
            : `Row in ${target.notePath ?? ""}`;
    el.createEl("small", { text: where });
  }
  onChooseSuggestion(profile: Profile): void {
    this.onPick(profile);
  }
}

export interface CaptureCommandDeps {
  readonly app: App;
  readonly store: ProfileStore;
  readonly dataService: DataService;
}

/**
 * Capture whatever is on the clipboard into a chosen view.
 *
 * Duplicates are reported rather than blocked: the check runs on identity fields only, and a match is shown
 * with what it matched on so the person decides. Capture that silently refuses is worse than one that
 * occasionally asks.
 */
export async function captureFromClipboard(deps: CaptureCommandDeps): Promise<void> {
  const { app, store, dataService } = deps;
  const profiles = store.listProfiles();
  if (profiles.length === 0) {
    new Notice("Create a view first — captures need somewhere to land.");
    return;
  }

  let text = "";
  try {
    text = await navigator.clipboard.readText();
  } catch {
    new Notice("Couldn't read the clipboard.");
    return;
  }

  const payload = parseCaptureText(text);
  if (payload.fields.length === 0 && payload.url === undefined) {
    new Notice("Nothing on the clipboard to capture.");
    return;
  }

  new ViewPicker(app, profiles, (profile) => {
    void (async () => {
      const target = effectiveTarget(profile);
      if (target === null) {
        new Notice(`“${profile.name}” has no capture target — set one in the view's settings.`);
        return;
      }

      const result = await dataService.query({ ...profile, pageSize: null }, {});
      const columns = captureColumnsFor(profile, result.rows);
      const { values, unmapped } = prepareCapture(payload, columns);
      if (Object.keys(values).length === 0) {
        new Notice("Nothing in that text matched this view's columns.");
        return;
      }

      const duplicate = findDuplicate(values, columns, result.rows);
      const service = new CaptureService(app, () => store.getSettings().noteTemplates);
      const written = await service.commit(target, values, columns, payload);
      if (!written.ok) {
        new Notice(`Couldn't capture: ${written.reason ?? "unknown error"}`);
        return;
      }
      if (written.path !== undefined) dataService.invalidate(written.path);

      const parts = [`Captured into “${profile.name}”`];
      if (written.createdTable === true) parts.push("(created the table)");
      if (duplicate !== null) parts.push(`— note: ${duplicate.on} already appears in this view`);
      if (unmapped.length > 0) parts.push(`· ${unmapped.length} field(s) had no column`);
      new Notice(parts.join(" "));
    })();
  }).open();
}
