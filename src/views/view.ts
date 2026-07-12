import type { App, Component } from "obsidian";
import type { Row, SortKey, TransformResult } from "../domain/index";
import type { Profile } from "../services/index";
import type { ResolvedColumn } from "./view-model";
import type { CellRendererRegistry } from "./cells/cell-renderer";
import type { CellEditorRegistry } from "./cells/cell-editor";
import type { CopyFormat } from "./copy/row-copy";

/** Everything a view needs to paint itself. Provided fresh on each render. */
export interface ViewRenderContext {
  readonly container: HTMLElement;
  readonly result: TransformResult;
  readonly profile: Profile;
  readonly columns: readonly ResolvedColumn[];
  readonly cellRenderers: CellRendererRegistry;
  readonly app: App;
  readonly sourcePath: string;
  readonly component: Component;
  /** Stable identity for this view instance, for view-local UI state across re-renders. */
  readonly viewKey: string;
  readonly currentSort: readonly SortKey[];
  /** A view requests a new sort; the host re-queries and re-renders. */
  readonly onSortChange: (keys: SortKey[]) => void;

  // Editing (present only when inline editing is enabled for this render).
  readonly cellEditors?: CellEditorRegistry;
  readonly onEditCell?: (row: Row, column: string, value: string) => void;
  readonly onDeleteRow?: (row: Row) => void;
  readonly onAddRow?: (row: Row) => void;
  readonly onDuplicateRow?: (row: Row) => void;
  readonly onBulkEdit?: (rows: readonly Row[], column: string, value: string) => void;
  readonly onBulkDelete?: (rows: readonly Row[]) => void;
  /** Insert a Pandoc citation for a row into the last-edited note (academic kit views). */
  readonly onCite?: (citeKey: string) => void;
  /** Fill a library row's empty metadata cells from its DOI (academic kit views). */
  readonly onFetchDoi?: (row: Row) => void;
  /** Promote a library row to a dedicated note, pre-seeded + linked back (academic kit views). */
  readonly onPromote?: (row: Row) => void;
  /** Distinct existing values for a column, for select/theme autocomplete. */
  readonly columnValues?: (columnName: string) => readonly string[];
  /** Add a new blank row and open its editor card immediately (toolbar affordance). */
  readonly onAddRowTop?: () => void;
  /** Show nested tags by their last segment only. */
  readonly shortenTags?: boolean;
  /** Look up metadata for a DOI (card fetch button); returns column→value to fill, or null. */
  readonly onFetchDoiValues?: (doi: string) => Promise<Record<string, string> | null>;
  /** Find which library papers a DOI cites (card button). */
  readonly onFindCitations?: (doi: string) => Promise<Record<string, string> | null>;
  /** Persist a new column width (drag-to-resize); only wired in the dashboard pane. */
  readonly onResizeColumn?: (name: string, width: number) => void;
  /** Reset one column's width to its default (remove its stored width). */
  readonly onResetColumnWidth?: (name: string) => void;
  /** Clear every custom column width, returning the table to its natural layout. */
  readonly onResetAllColumnWidths?: () => void;
  /** Hide a data column from the view (space optimisation, invoked from the header). */
  readonly onHideColumn?: (name: string) => void;

  // Row copying (present only when the opt-in "row copying" feature is enabled).
  /** Copy the given rows to the clipboard. `format` omitted = the smart default (markdown + html). */
  readonly onCopyRows?: (rows: readonly Row[], format?: CopyFormat) => void;
  /** Whether Cmd/Ctrl+C (with the table focused and rows selected) triggers onCopyRows. */
  readonly copyOnShortcut?: boolean;
  /** Current copy defaults + quick toggles, surfaced in the "Copy as…" menu. */
  readonly copyOptions?: {
    readonly includeHeader: boolean;
    readonly stripLinks: boolean;
    readonly onToggleHeader: () => void;
    readonly onToggleStripLinks: () => void;
  };

  /** Persist a view-option value (presentation tuning like gallery size/ratio/fit). Wired in the
   *  dashboard pane; absent in read-only hosts. */
  readonly onSetViewOption?: (key: string, value: unknown) => void;

  /** Optional context that lets an empty view diagnose itself and offer one-tap remedies.
   *  Provided by the dashboard pane; absent in read-only hosts (e.g. code blocks). */
  readonly emptyState?: {
    /** Human-readable scope, e.g. "the Research folder" or "your whole vault". */
    readonly scopeLabel: string;
    /** Whether a filter or advanced query is active (so 0 rows means "filtered out"). */
    readonly hasFilter: boolean;
    /** Clear the filter and advanced query for this view. */
    readonly onClearFilters: () => void;
    /** Open this view's settings (to change folder, sources, columns, …). */
    readonly onOpenSettings: () => void;
  };
}

/** Describes one configurable option a view exposes, rendered generically in settings. */
export type ViewOptionKind = "field" | "select" | "toggle" | "text" | "number";

export interface ViewOptionSpec {
  readonly key: string;
  readonly label: string;
  readonly kind: ViewOptionKind;
  readonly description?: string;
  /** Choices for `select`. */
  readonly choices?: readonly { readonly value: string; readonly label: string }[];
  /** For `field`, restrict the offered fields (e.g. only date fields). */
  readonly fieldFilter?: "any" | "date";
  readonly placeholder?: string;
}

/**
 * A pluggable view. Adding Kanban, pivot, calendar, or chart later means writing
 * one module that implements this interface and registering it.
 */
export interface KnowledgeView {
  readonly type: string;
  readonly label: string;
  readonly icon?: string;
  /** When false, the view receives all filtered rows rather than a single page. */
  readonly paginates?: boolean;
  /** Optional view-specific settings, surfaced automatically in the editor modal. */
  readonly optionSpecs?: readonly ViewOptionSpec[];
  render(context: ViewRenderContext): void;
}
