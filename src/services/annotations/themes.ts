/**
 * Colour → research-theme mapping. Highlight colour carries meaning (yellow = key finding, blue =
 * method, …). Stored as a simple "color=Theme; color=Theme" string so it's editable in one field.
 */
export const DEFAULT_THEME_SPEC = "yellow=Key finding; green=Evidence; blue=Method; red=Limitation; purple=Definition; orange=Example";

export function parseThemeMap(spec: string): Record<string, string> {
  const map: Record<string, string> = {};
  for (const part of spec.split(/[;\n]/)) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    const color = part.slice(0, eq).trim().toLowerCase();
    const theme = part.slice(eq + 1).trim();
    if (color !== "" && theme !== "") map[color] = theme;
  }
  return map;
}

/** Theme label for a colour name, or null when unmapped. */
export function themeForColor(colorName: string, map: Record<string, string>): string | null {
  return map[colorName.toLowerCase()] ?? null;
}
