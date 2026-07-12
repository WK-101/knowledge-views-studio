import type { ColumnConfig, ScopeConfig, SortKey } from "../domain/index";
import { createProfile, type GlobalSettings, type Profile } from "../services/index";
import type { ViewBlockConfig } from "./config";

/**
 * Merge a parsed block config over a referenced profile (or a fresh default) to
 * produce the effective profile to render. Pure, so block resolution is tested
 * without touching Obsidian.
 */
export function resolveBlockProfile(
  config: ViewBlockConfig,
  referenced: Profile | undefined,
  settings: GlobalSettings,
): Profile {
  const base =
    referenced ??
    createProfile({
      pageSize: settings.defaultPageSize,
      view: { type: settings.defaultView, options: {} },
    });

  const scope: ScopeConfig =
    config.folders && config.folders.length > 0
      ? { mode: "folders", folders: config.folders, includeSubfolders: true }
      : base.scope;

  const columns: ColumnConfig[] = config.columns
    ? config.columns.map((c) => ({ name: c.name, type: c.type ?? "text" }))
    : [...base.columns];

  const sort: SortKey[] = config.sort
    ? config.sort.map((s) => ({ field: s.field, direction: s.direction }))
    : [...base.sort];

  return createProfile({
    ...base,
    scope,
    columns,
    sort,
    extractors: config.extractors ?? [...base.extractors],
    advancedQuery: config.query ?? base.advancedQuery,
    group: config.group ? { field: config.group } : base.group,
    view: {
      type: config.view ?? base.view.type,
      options: { ...base.view.options, ...(config.viewOptions ?? {}) },
    },
    pageSize: config.limit ?? base.pageSize,
  });
}
