import { createId } from "../../util/id";
import { normalizeLayout, type Layout, type Profile } from "./profile";

/**
 * The layouts ("tabs") of a view: its explicit `layouts` when set, otherwise a single default layout
 * derived from the profile's own presentation fields. So callers can treat every view as having one
 * or more layouts without special-casing the legacy single-layout shape.
 */
export function profileLayouts(profile: Profile): Layout[] {
  if (profile.layouts && profile.layouts.length > 0) return [...profile.layouts];
  return [layoutFromProfile(profile)];
}

/** Snapshot a profile's presentation as a standalone Layout (used for the default layout + editing). */
export function layoutFromProfile(profile: Profile, name?: string): Layout {
  return normalizeLayout({
    id: createId("layout"),
    ...(name ? { name } : {}),
    view: profile.view,
    sort: profile.sort,
    group: profile.group,
    pageSize: profile.pageSize,
    hiddenColumns: profile.hiddenColumns,
    ...(profile.columnWidths ? { columnWidths: profile.columnWidths } : {}),
    frozenFirstColumn: profile.frozenFirstColumn,
    frozenHeader: profile.frozenHeader,
    rowHeight: profile.rowHeight,
    tableWidth: profile.tableWidth,
    sourceColumn: profile.sourceColumn,
    rowSelection: profile.rowSelection,
    hideEmptyColumns: profile.hideEmptyColumns,
  });
}

/**
 * Merge a view's shared data with one layout's presentation into a plain Profile — exactly the shape
 * the pipeline, the views, and the dashboard already consume. The data source (scope, extractors,
 * columns, computed, rollups, filter, advanced query, column matching, source options) comes from the
 * profile and is identical across layouts; everything presentational comes from the layout.
 */
export function composeLayout(profile: Profile, layout: Layout): Profile {
  // Drop the profile's own presentational width map and the layouts list; the layout supplies both.
  const { layouts: _layouts, columnWidths: _widths, ...shared } = profile;
  void _layouts;
  void _widths;
  return {
    ...shared,
    view: layout.view,
    sort: layout.sort,
    group: layout.group,
    pageSize: layout.pageSize,
    hiddenColumns: layout.hiddenColumns,
    frozenFirstColumn: layout.frozenFirstColumn,
    frozenHeader: layout.frozenHeader,
    rowHeight: layout.rowHeight,
    tableWidth: layout.tableWidth,
    sourceColumn: layout.sourceColumn,
    rowSelection: layout.rowSelection,
    hideEmptyColumns: layout.hideEmptyColumns,
    ...(layout.columnWidths ? { columnWidths: layout.columnWidths } : {}),
  };
}

/** Whether a profile actually has multiple layouts (i.e. is a multi-layout view). */
export function hasMultipleLayouts(profile: Profile): boolean {
  return (profile.layouts?.length ?? 0) > 1;
}

/** Presentation fields that belong to a layout; everything else is shared view data. */
const LAYOUT_FIELD_KEYS: ReadonlySet<string> = new Set([
  "view",
  "sort",
  "group",
  "pageSize",
  "hiddenColumns",
  "columnWidths",
  "frozenFirstColumn",
  "frozenHeader",
  "rowHeight",
  "tableWidth",
  "sourceColumn",
  "rowSelection",
  "hideEmptyColumns",
]);

/**
 * Split an edit to a view into the part that changes shared data (scope, filter, columns, …) and the
 * part that changes one layout's presentation (view type, sort, grouping, visible columns, …). Used
 * so a dashboard edit lands in the right place: data on the view, presentation on the active layout.
 */
export function splitViewPatch(patch: Partial<Profile>): { data: Partial<Profile>; layout: Partial<Layout> } {
  const data: Record<string, unknown> = {};
  const layout: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(patch)) {
    (LAYOUT_FIELD_KEYS.has(key) ? layout : data)[key] = value;
  }
  return { data: data, layout: layout };
}
