/**
 * One design language for everything the extension paints onto a page.
 *
 * The toolbar, the highlight menu, the highlights sidebar, and the table-capture action all live in
 * separate shadow roots, and each used to carry its own colours, radii, and shadows — so they read as four
 * different tools bolted onto the same page. These tokens are the single source they now share, so a
 * highlight menu and a table action look like siblings, in light mode and dark.
 *
 * `isDark` is passed in rather than read here, so the pure values stay testable and each caller decides how
 * it detects the page's colour scheme.
 */

export interface InPageTheme {
  readonly bg: string;
  readonly fg: string;
  readonly muted: string;
  readonly faint: string;
  readonly line: string;
  readonly hover: string;
  readonly accent: string;
  readonly accentHover: string;
  readonly accentInk: string;
  readonly danger: string;
  readonly dangerWash: string;
  readonly radius: string;
  readonly radiusSmall: string;
  readonly shadow: string;
  readonly font: string;
}

export function inPageTheme(isDark: boolean): InPageTheme {
  return {
    bg: isDark ? "#232327" : "#ffffff",
    fg: isDark ? "#e9e8e6" : "#37352f",
    muted: isDark ? "#9c9a94" : "#7a776f",
    faint: isDark ? "#7d7b75" : "#9b988f",
    line: isDark ? "#34343a" : "#ebeae7",
    hover: isDark ? "#2a2a30" : "#f1f0ee",
    accent: "#7c5cff",
    accentHover: "#6a4ae8",
    accentInk: "#ffffff",
    danger: "#e0526a",
    dangerWash: isDark ? "#3a2a2e" : "#fdeaee",
    radius: "11px",
    radiusSmall: "7px",
    shadow: "0 8px 30px rgba(0,0,0,0.18), 0 1px 2px rgba(0,0,0,0.06)",
    font: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  };
}

/**
 * The shared base rules — a reset, the font, and a few primitives (a pill container, a small button, a
 * primary button, a divider) — so every surface starts from the same foundation and only adds what's
 * unique to it. Scope everything under a single class the caller sets on its root element.
 */
export function inPageBaseCss(t: InPageTheme, scope = ".kvs-ui"): string {
  return `
    :host { all: initial; }
    ${scope} *, ${scope} { box-sizing: border-box; font-family: ${t.font}; -webkit-font-smoothing: antialiased; }
    ${scope} {
      background: ${t.bg}; color: ${t.fg}; border: 1px solid ${t.line};
      border-radius: ${t.radius}; box-shadow: ${t.shadow}; font-size: 12.5px; line-height: 1.5;
    }
    ${scope} .kbtn {
      border: 1px solid ${t.line}; background: ${t.bg}; color: ${t.fg}; font: inherit; font-weight: 550;
      padding: 4px 9px; border-radius: ${t.radiusSmall}; cursor: pointer;
      transition: background 0.12s ease, border-color 0.12s ease;
    }
    ${scope} .kbtn:hover { background: ${t.hover}; border-color: ${t.muted}; }
    ${scope} .kbtn[disabled] { opacity: 0.6; cursor: default; }
    ${scope} .kbtn-primary { background: ${t.accent}; border-color: ${t.accent}; color: ${t.accentInk}; }
    ${scope} .kbtn-primary:hover { background: ${t.accentHover}; border-color: ${t.accentHover}; }
    ${scope} .kbtn-icon { padding: 4px 6px; }
    ${scope} .kdivider { width: 1px; align-self: stretch; background: ${t.line}; margin: 2px 1px; }
    ${scope} .kmuted { color: ${t.muted}; }
  `;
}

/**
 * Transparency for a painted highlight: the alpha to use for a given intensity, split by colour scheme so
 * a highlight reads the same weight on a white page and a dark one. "Light" is a wash, "strong" is emphatic.
 */
export function highlightAlpha(intensity: string, isDark: boolean): number {
  const key = intensity === "light" || intensity === "strong" ? intensity : "medium";
  const table: Record<string, { light: number; dark: number }> = {
    light: { light: 0.18, dark: 0.16 },
    medium: { light: 0.34, dark: 0.3 },
    strong: { light: 0.52, dark: 0.46 },
  };
  const row = table[key] ?? table["medium"]!;
  return isDark ? row.dark : row.light;
}
