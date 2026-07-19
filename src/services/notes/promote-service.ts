import { normalizePath, TFile, type App } from "obsidian";
import { renderTemplate } from "../../../shared/template";
import type { Row } from "../../domain/index";
import { getField } from "../../domain/fields";
import { dedicatedNoteKeyFor, findDedicatedNote } from "./dedicated-note";
import { DEFAULT_WEB_PROMOTED_TEMPLATE, promotionPlan } from "./promotion-plan";

/**
 * Creating (or finding) a row's dedicated note, from anywhere.
 *
 * The dashboard has long done this for papers, tangled up with Zotero enrichment and workspace calls. This
 * is the general, callable version: the bridge can promote a row the companion is looking at, and the same
 * code serves any non-academic view in-app later. Finding-before-creating is what makes it idempotent —
 * promote twice and the second call opens what the first one made.
 */

export interface PromoteDeps {
  readonly app: App;
  /** Apply the wikilink back into the row. Reuses the same writer as every other edit. */
  readonly editCell: (row: Row, column: string, value: string) => Promise<void>;
}

export interface PromoteProfileBits {
  readonly academicKit?: boolean;
  readonly dedicatedNoteKey?: string;
  readonly promotedNotesFolder?: string;
  readonly promotedNoteTemplate?: string;
  readonly scopeFolder?: string;
}

export interface PromoteOutcome {
  readonly ok: boolean;
  readonly path?: string;
  /** False when an existing note was found rather than a new one written. */
  readonly created?: boolean;
  /** True when the row's link column was filled in on the way. */
  readonly linked?: boolean;
  readonly reason?: string;
}

export class PromotionService {
  constructor(private readonly deps: PromoteDeps) {}

  async promote(
    profile: PromoteProfileBits,
    row: Row,
    columns: readonly { readonly name: string; readonly type?: string }[],
    fallbackTemplate = "",
  ): Promise<PromoteOutcome> {
    const cells: Record<string, string> = {};
    for (const column of columns) cells[column.name] = getField(row, column.name);

    const matchKey = dedicatedNoteKeyFor(profile);
    const plan = promotionPlan({
      cells,
      columns,
      matchKey,
      ...(profile.promotedNotesFolder !== undefined ? { configuredFolder: profile.promotedNotesFolder } : {}),
      ...(profile.scopeFolder !== undefined ? { scopeFolder: profile.scopeFolder } : {}),
    });

    if (plan.matchValue === "") {
      return {
        ok: false,
        reason: `This row has no ${matchKey === "source" ? "URL" : matchKey} to identify its note by.`,
      };
    }

    // Found rather than created: promotion is idempotent, and a second promote must not make a duplicate.
    const existing = findDedicatedNote(this.deps.app, matchKey, plan.matchValue);
    if (existing !== null) {
      const linked = await this.backfillLink(row, plan.noteLinkColumn, existing);
      return { ok: true, path: existing.path, created: false, linked };
    }

    const template =
      (profile.promotedNoteTemplate ?? "").trim() ||
      fallbackTemplate.trim() ||
      DEFAULT_WEB_PROMOTED_TEMPLATE;

    // The note must carry its identity, or the next promote can't find it. When the template's frontmatter
    // doesn't already write the key, it's prepended — a note that loses its identity is an orphan.
    let content = renderTemplate(template, plan.variables);
    const carriesKey = new RegExp(`^${matchKey}\\s*:`, "im").test(content.split("\n---")[0] ?? "");
    if (!carriesKey) {
      content = content.startsWith("---\n")
        ? content.replace("---\n", `---\n${matchKey}: ${plan.matchValue}\n`)
        : `---\n${matchKey}: ${plan.matchValue}\n---\n\n${content}`;
    }

    await this.ensureFolder(plan.folder);
    let path = normalizePath(`${plan.folder}/${plan.fileBase}.md`);
    for (let n = 2; this.deps.app.vault.getAbstractFileByPath(path) !== null; n++) {
      path = normalizePath(`${plan.folder}/${plan.fileBase} ${String(n)}.md`);
    }
    const file = await this.deps.app.vault.create(path, content);
    const linked = await this.backfillLink(row, plan.noteLinkColumn, file);
    return { ok: true, path, created: true, linked };
  }

  /** Fill the row's link column when it's empty, so the row shows where its note is. */
  private async backfillLink(row: Row, column: string | null, note: TFile): Promise<boolean> {
    if (column === null) return false;
    if (getField(row, column).trim() !== "") return false;
    const name = note.path.replace(/\.md$/, "").split("/").pop() ?? note.basename;
    try {
      await this.deps.editCell(row, column, `[[${name}]]`);
      return true;
    } catch {
      // The note exists either way; a failed backfill mustn't turn success into failure.
      return false;
    }
  }

  private async ensureFolder(folder: string): Promise<void> {
    const parts = folder.split("/").filter((part) => part !== "");
    let acc = "";
    for (const part of parts) {
      acc = acc === "" ? part : `${acc}/${part}`;
      if (this.deps.app.vault.getAbstractFileByPath(acc) === null) {
        await this.deps.app.vault.createFolder(acc);
      }
    }
  }
}
