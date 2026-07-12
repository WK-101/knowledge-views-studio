/**
 * Parser for the body of a ```knowledge-view``` code block. A small, forgiving
 * `key: value` format (no YAML dependency) — pure and unit-tested.
 */
export interface BlockSortSpec {
  readonly field: string;
  readonly direction: "asc" | "desc";
}

export interface BlockColumnSpec {
  readonly name: string;
  readonly type?: string;
}

export interface ViewBlockConfig {
  profile?: string;
  view?: string;
  folders?: string[];
  query?: string;
  group?: string;
  limit?: number;
  sort?: BlockSortSpec[];
  columns?: BlockColumnSpec[];
  extractors?: string[];
  viewOptions?: Record<string, string>;
}

const splitList = (value: string): string[] =>
  value
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

function parseSortEntry(entry: string): BlockSortSpec {
  const parts = entry.trim().split(/\s+/);
  const last = parts.length > 1 ? (parts[parts.length - 1] ?? "").toLowerCase() : "";
  if (last === "asc" || last === "desc") {
    return { field: parts.slice(0, -1).join(" "), direction: last };
  }
  return { field: entry.trim(), direction: "asc" };
}

function parseColumns(value: string): BlockColumnSpec[] {
  return splitList(value)
    .map((entry) => {
      const idx = entry.indexOf(":");
      return idx >= 0
        ? { name: entry.slice(0, idx).trim(), type: entry.slice(idx + 1).trim() }
        : { name: entry.trim() };
    })
    .filter((c) => c.name !== "");
}

export function parseViewBlock(source: string): ViewBlockConfig {
  const config: ViewBlockConfig = {};

  for (const raw of source.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || line.startsWith("#") || line.startsWith("//")) continue;

    const idx = line.indexOf(":");
    if (idx < 0) continue;
    const rawKey = line.slice(0, idx).trim();
    const key = rawKey.toLowerCase();
    const value = line.slice(idx + 1).trim();
    if (value === "") continue;

    switch (key) {
      case "profile":
        config.profile = value;
        break;
      case "view":
        config.view = value.toLowerCase();
        break;
      case "folder":
      case "folders":
        config.folders = splitList(value);
        break;
      case "query":
      case "filter":
      case "where":
        config.query = value;
        break;
      case "group":
      case "group-by":
        config.group = value;
        break;
      case "limit":
      case "page-size": {
        const n = Number(value);
        if (Number.isFinite(n) && n > 0) config.limit = Math.floor(n);
        break;
      }
      case "sort":
      case "sort-by":
        config.sort = splitList(value).map(parseSortEntry);
        break;
      case "columns":
      case "fields":
        config.columns = parseColumns(value);
        break;
      case "extractors":
      case "sources":
        config.extractors = splitList(value);
        break;
      default:
        if (key.startsWith("option.")) {
          const optKey = rawKey.slice(rawKey.indexOf(".") + 1).trim();
          if (optKey !== "") (config.viewOptions ??= {})[optKey] = value;
        }
        break;
    }
  }

  return config;
}
