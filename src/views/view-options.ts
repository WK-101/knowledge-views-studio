type Options = Readonly<Record<string, unknown>>;

export function optString(options: Options, key: string, fallback = ""): string {
  const value = options[key];
  return typeof value === "string" ? value : fallback;
}

export function optBool(options: Options, key: string, fallback = false): boolean {
  const value = options[key];
  return typeof value === "boolean" ? value : fallback;
}

export function optNumber(options: Options, key: string, fallback: number): number {
  const value = options[key];
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}
