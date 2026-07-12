import {
  BasesView,
  type BasesAllOptions,
  type BasesDropdownOption,
  type BasesPropertyOption,
  type BasesTextOption,
  type BasesToggleOption,
  type QueryController,
} from "obsidian";
import { inferFieldRole } from "../../domain/index";
import { createProfile } from "../../services/index";
import { buildRowsFromBasesData, type ExtractedBasesEntry } from "../../services/bases/bases-row";
import type {
  CellRendererRegistry,
  KnowledgeView,
  ResolvedColumn,
  ViewOptionSpec,
  ViewRenderContext,
} from "../../views/index";
import { createId } from "../../util/id";
import { valueToString } from "./value-extract";

export interface BasesViewDeps {
  readonly cellRenderers: CellRendererRegistry;
}

/** The Bases view id KVS registers for a given KVS view (e.g. "kvs-kanban"). */
export function basesViewId(view: KnowledgeView): string {
  return `kvs-${view.type}`;
}

/**
 * Lends a KVS view to Bases. Bases hands us its frontmatter/file data; we map each
 * entry onto the KVS `Row` model, synthesise a minimal profile from the Bases view
 * config, and render through the *existing, unchanged* KVS view core. Bases data has
 * no in-body table cell to write to, so the render context is intentionally
 * read-only (no edit/drag callbacks).
 */
export class KvsBasesView extends BasesView {
  type: string;
  private readonly root: HTMLElement;
  private readonly viewKey = createId("bases");

  constructor(
    controller: QueryController,
    containerEl: HTMLElement,
    private readonly view: KnowledgeView,
    private readonly deps: BasesViewDeps,
  ) {
    super(controller);
    this.type = basesViewId(view);
    this.root = containerEl.createDiv({ cls: "kvs-bases-host" });
  }

  override onDataUpdated(): void {
    const order = this.config.getOrder();
    const columns: ResolvedColumn[] = order.map((propId) => ({
      name: propId,
      label: this.config.getDisplayName(propId),
      typeId: "text",
      editable: false,
      role: inferFieldRole("text", this.config.getDisplayName(propId)),
    }));

    const extracted: ExtractedBasesEntry[] = this.data.data.map((entry, index) => {
      const cells: Record<string, string> = {};
      for (const propId of order) cells[propId] = valueToString(entry.getValue(propId));
      return {
        filePath: entry.file.path,
        fileName: entry.file.basename,
        folderPath: entry.file.parent?.path ?? "",
        createdMs: entry.file.stat.ctime,
        modifiedMs: entry.file.stat.mtime,
        sizeBytes: entry.file.stat.size,
        index,
        cells,
      };
    });

    const rows = buildRowsFromBasesData(extracted, order);
    const profile = createProfile({ columns: [], view: { type: this.view.type, options: this.synthesizeOptions() } });

    const context: ViewRenderContext = {
      container: this.root,
      result: { rows, groups: null, total: rows.length, gathered: rows.length, page: null },
      profile,
      columns,
      cellRenderers: this.deps.cellRenderers,
      app: this.app,
      sourcePath: "",
      component: this,
      viewKey: this.viewKey,
      currentSort: [],
      onSortChange: () => undefined,
    };
    this.view.render(context);
  }

  /** Read the Bases view-config values into the option keys the KVS view expects. */
  private synthesizeOptions(): Record<string, unknown> {
    const options: Record<string, unknown> = {};
    for (const spec of this.view.optionSpecs ?? []) {
      if (spec.kind === "field") {
        options[spec.key] = this.config.getAsPropertyId(spec.key) ?? "";
      } else if (spec.kind === "toggle") {
        options[spec.key] = Boolean(this.config.get(spec.key));
      } else if (spec.kind === "number") {
        const n = Number(this.config.get(spec.key));
        options[spec.key] = Number.isFinite(n) ? n : 0;
      } else {
        const value = this.config.get(spec.key);
        options[spec.key] = typeof value === "string" && value !== "" ? value : selectDefault(spec);
      }
    }
    return options;
  }
}

function selectDefault(spec: ViewOptionSpec): string {
  return spec.kind === "select" ? (spec.choices?.[0]?.value ?? "") : "";
}

/** Translate a KVS view's option specs into Bases config-menu options. */
export function basesOptionsFor(view: KnowledgeView): BasesAllOptions[] {
  const options: BasesAllOptions[] = [];
  for (const spec of view.optionSpecs ?? []) {
    if (spec.kind === "field") {
      const opt: BasesPropertyOption = {
        key: spec.key,
        type: "property",
        displayName: spec.label,
        placeholder: "Select property",
      };
      options.push(opt);
    } else if (spec.kind === "select") {
      const choices: Record<string, string> = {};
      for (const choice of spec.choices ?? []) choices[choice.value] = choice.label;
      const opt: BasesDropdownOption = {
        key: spec.key,
        type: "dropdown",
        displayName: spec.label,
        options: choices,
        ...(spec.choices?.[0] ? { default: spec.choices[0].value } : {}),
      };
      options.push(opt);
    } else if (spec.kind === "toggle") {
      const opt: BasesToggleOption = { key: spec.key, type: "toggle", displayName: spec.label, default: false };
      options.push(opt);
    } else {
      const opt: BasesTextOption = {
        key: spec.key,
        type: "text",
        displayName: spec.label,
        ...(spec.placeholder ? { placeholder: spec.placeholder } : {}),
      };
      options.push(opt);
    }
  }
  return options;
}
