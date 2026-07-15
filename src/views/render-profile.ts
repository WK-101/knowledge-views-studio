import type { App, Component } from "obsidian";
import type { ColumnTypeRegistry, PageInfo, Row, SortKey } from "../domain/index";
import type { DataService, Profile, UndoManager, WriterService } from "../services/index";
import type { ViewRegistry } from "./registry";
import type { CellRendererRegistry } from "./cells/cell-renderer";
import type { CellEditorRegistry } from "./cells/cell-editor";
import type { EditingHandlers } from "./editing";
import type { CopyFormat } from "./copy/row-copy";
import { resolveColumns, type ResolvedColumn } from "./view-model";
import { isVirtualField } from "../domain/index";
import type { ViewRenderContext } from "./view";

/** When "hide empty columns" is on, drop data columns that are blank in every matching row. Virtual
 *  columns (note/path/…) are always kept — they derive from the file, not from a possibly-blank cell. */
function filterEmptyColumns(
  columns: ResolvedColumn[],
  profile: Profile,
  nonEmptyFields: readonly string[] | undefined,
): ResolvedColumn[] {
  if (!profile.hideEmptyColumns || !nonEmptyFields) return columns;
  const nonEmpty = new Set(nonEmptyFields);
  return columns.filter((c) => isVirtualField(c.name) || nonEmpty.has(c.name.toLowerCase()));
}

export interface RenderProfileDeps {
  readonly app: App;
  readonly dataService: DataService;
  readonly views: ViewRegistry;
  readonly cellRenderers: CellRendererRegistry;
  readonly cellEditors: CellEditorRegistry;
  readonly registry: ColumnTypeRegistry;
  readonly writer: WriterService;
  readonly undo: UndoManager;
}

export interface RenderProfileOptions {
  readonly container: HTMLElement;
  readonly profile: Profile;
  readonly deps: RenderProfileDeps;
  readonly component: Component;
  readonly sourcePath: string;
  /** Stable per-host identity so views can keep UI state across re-renders. */
  readonly viewKey: string;
  readonly onSortChange: (keys: SortKey[]) => void;
  readonly onSetViewOption?: (key: string, value: unknown) => void;
  readonly onSetColumnSummary?: (column: string, fn: string) => void;
  /** Safety cap on rows handed to a view (0 = no cap). */
  readonly maxRows?: number;
  /** Transient free-text search applied after the profile's own filter. */
  readonly search?: string;
  /** Zero-based page index for paginated views. */
  readonly page?: number;
  /** Persist a dragged column width (dashboard only). */
  readonly onResizeColumn?: (name: string, width: number) => void;
  readonly onResetColumnWidth?: (name: string) => void;
  readonly onResetAllColumnWidths?: () => void;
  readonly onHideColumn?: (name: string) => void;
  /** Called after each successful query with the rows the view received. */
  readonly onResult?: (info: { rows: Row[]; total: number; page: PageInfo | null }) => void;
  /** Map each result row through this before rendering — used to overlay not-yet-saved edits. */
  readonly overlayRow?: (row: Row) => Row;
  /** Return true to abort this render's DOM writes — set when a newer render has superseded it. */
  readonly shouldAbort?: () => boolean;
  /** When provided, cells become editable and rows get add/delete/bulk actions. */
  readonly editing?: EditingHandlers;
  /** Optional self-diagnosing empty-state context + remedies (dashboard pane only). */
  readonly emptyState?: ViewRenderContext["emptyState"];
  /** Opt-in row copying: a handler to copy rows, and whether the keyboard shortcut is active. */
  readonly onCopyRows?: (rows: readonly Row[], format?: CopyFormat) => void;
  readonly copyOnShortcut?: boolean;
  readonly copyOptions?: ViewRenderContext["copyOptions"];
  /** Academic kit row actions + autocomplete, forwarded to the view context. */
  readonly onCite?: (citeKey: string) => void;
  readonly onFetchDoi?: (row: Row) => void;
  readonly onPromote?: (row: Row) => void;
  readonly columnValues?: (columnName: string) => readonly string[];
  readonly onAddRowTop?: () => void;
  readonly shortenTags?: boolean;
  readonly onFetchDoiValues?: (doi: string) => Promise<Record<string, string> | null>;
  readonly onFindCitations?: (doi: string) => Promise<Record<string, string> | null>;
}

/**
 * The single rendering entry point shared by the code-block processor and the
 * workspace pane: query through the cached DataService, resolve columns, pick the
 * view, and paint. Aggregate views (paginates === false) receive every filtered
 * row; an optional safety cap protects against pathologically large datasets.
 */
export async function renderProfile(options: RenderProfileOptions): Promise<void> {
  const { container, profile, deps, component, sourcePath, onSortChange, editing, viewKey } = options;
  const maxRows = options.maxRows ?? 0;
  // Modular kit styling: only views that opt in get the academic look, not the whole plugin.
  container.toggleClass("kvs-kit-academic", Boolean(profile.academicKit));

  try {
    const view = deps.views.get(profile.view.type);
    const effectiveProfile = view && view.paginates === false ? { ...profile, pageSize: null } : profile;
    // Keep the current view on screen and query first. Only show a loading indicator if the
    // query is actually slow (e.g. the first read of a large vault) — cached queries resolve
    // instantly, so re-renders on sort/filter/search/paging no longer flash "Loading…".
    const queryPromise = deps.dataService.query(effectiveProfile, { search: options.search, page: options.page });
    const loadingTimer = window.setTimeout(() => {
      if (options.shouldAbort?.()) return; // a newer render is in flight — don't touch the DOM
      container.empty();
      container.createDiv({ cls: "kvs-loading", text: "Loading…" });
    }, 120);
    let result;
    try {
      result = await queryPromise;
    } finally {
      window.clearTimeout(loadingTimer);
    }
    if (options.shouldAbort?.()) return; // superseded while awaiting — leave the newer render's DOM intact
    container.empty();

    if (!view) {
      container.createDiv({ cls: "kvs-error", text: `Unknown view "${profile.view.type}".` });
      return;
    }

    const truncated = maxRows > 0 && result.groups === null && result.rows.length > maxRows;
    if (truncated) {
      container.createDiv({
        cls: "kvs-banner",
        text: `Showing the first ${maxRows} of ${result.total} rows. Add a filter or set a page size to see the rest.`,
      });
    }
    const rawViewResult = truncated ? { ...result, rows: result.rows.slice(0, maxRows) } : result;
    // Optimistic overlay: reflect not-yet-saved edits so the grid (and virtualized re-renders on
    // scroll) show pending values immediately, without waiting for the write + re-read.
    const overlay = options.overlayRow;
    const viewResult = overlay
      ? {
          ...rawViewResult,
          rows: rawViewResult.rows.map(overlay),
          groups: rawViewResult.groups
            ? rawViewResult.groups.map((g) => ({ ...g, rows: g.rows.map(overlay) }))
            : rawViewResult.groups,
        }
      : rawViewResult;
    // A view embedded in a note lives at whatever width the note column gives it — narrow in a split
    // editor, wide in a full pane. The host is its own container context so each layout (board columns,
    // gallery cards, the table toolbar) reads that width directly, independent of the window.
    const viewHost = container.createDiv({ cls: "kvs-view-host kvs-cq-view" });
    options.onResult?.({ rows: viewResult.rows, total: result.total, page: result.page });

    const context: ViewRenderContext = {
      container: viewHost,
      result: viewResult,
      profile,
      columns: filterEmptyColumns(resolveColumns(profile, viewResult.rows), profile, result.nonEmptyFields),
      cellRenderers: deps.cellRenderers,
      app: deps.app,
      sourcePath,
      viewKey,
      component,
      currentSort: [...profile.sort],
      onSortChange,
      ...(options.onSetViewOption ? { onSetViewOption: options.onSetViewOption } : {}),
      ...(options.onSetColumnSummary ? { onSetColumnSummary: options.onSetColumnSummary } : {}),
      ...(options.onResizeColumn ? { onResizeColumn: options.onResizeColumn } : {}),
      ...(options.onResetColumnWidth ? { onResetColumnWidth: options.onResetColumnWidth } : {}),
      ...(options.onResetAllColumnWidths ? { onResetAllColumnWidths: options.onResetAllColumnWidths } : {}),
      ...(options.onHideColumn ? { onHideColumn: options.onHideColumn } : {}),
      ...(options.emptyState ? { emptyState: options.emptyState } : {}),
      ...(options.onCopyRows ? { onCopyRows: options.onCopyRows, copyOnShortcut: Boolean(options.copyOnShortcut) } : {}),
      ...(options.copyOptions ? { copyOptions: options.copyOptions } : {}),
      ...(editing
        ? {
            cellEditors: deps.cellEditors,
            onEditCell: editing.onEditCell,
            onDeleteRow: editing.onDeleteRow,
            onAddRow: editing.onAddRow,
            onDuplicateRow: editing.onDuplicateRow,
            onBulkEdit: editing.onBulkEdit,
            onBulkDelete: editing.onBulkDelete,
          }
        : {}),
      ...(options.onCite ? { onCite: options.onCite } : {}),
      ...(options.onFetchDoi ? { onFetchDoi: options.onFetchDoi } : {}),
      ...(options.onPromote ? { onPromote: options.onPromote } : {}),
      ...(options.columnValues ? { columnValues: options.columnValues } : {}),
      ...(options.onAddRowTop ? { onAddRowTop: options.onAddRowTop } : {}),
      ...(options.shortenTags ? { shortenTags: true } : {}),
      ...(options.onFetchDoiValues ? { onFetchDoiValues: options.onFetchDoiValues } : {}),
      ...(options.onFindCitations ? { onFindCitations: options.onFindCitations } : {}),
    };
    // Load any heavy dependency this view defers (chart.js, today) before drawing. Awaited here so
    // `render` stays synchronous; a no-op for every view that doesn't define it.
    if (view.prepare) await view.prepare();
    view.render(context);
  } catch (error) {
    container.empty();
    const message = error instanceof Error ? error.message : String(error);
    container.createDiv({ cls: "kvs-error", text: `Knowledge Views Studio: ${message}` });
  }
}
