import { setIcon, setTooltip } from "obsidian";

/**
 * UI primitives for the view-settings editor.
 *
 * Obsidian's `Setting` row (label left, control right) is the right shape for an independent scalar
 * option, and we keep using it for those. It is the wrong shape for three things this editor is full of,
 * and building them out of `Setting` is what made the editor feel assembled rather than designed:
 *
 *   - repeating records (columns, rollups) — a stack of eight label/control rows per column;
 *   - multi-select pickers (folder scope) — squeezed into the narrow right-hand control column;
 *   - option sets (what becomes a row) — bare toggles with no room to explain themselves.
 *
 * These primitives give those three shapes a proper form.
 */

// ---------------------------------------------------------------- headers

export interface PanelHeadOptions {
  readonly title: string;
  readonly desc?: string;
  readonly actions?: (bar: HTMLElement) => void;
}

/** A panel's title block: heading, one-line explanation, and its primary actions on the right. */
export function panelHead(parent: HTMLElement, options: PanelHeadOptions): void {
  const head = parent.createDiv({ cls: "kvs-ph" });
  const text = head.createDiv({ cls: "kvs-ph-text" });
  text.createDiv({ cls: "kvs-ph-title", text: options.title });
  if (options.desc) text.createDiv({ cls: "kvs-ph-desc", text: options.desc });
  if (options.actions) options.actions(head.createDiv({ cls: "kvs-ph-actions" }));
}

/** A labelled group divider inside a panel. */
export function groupHead(parent: HTMLElement, title: string, desc?: string): void {
  const g = parent.createDiv({ cls: "kvs-gh" });
  g.createSpan({ cls: "kvs-gh-title", text: title });
  if (desc) g.createSpan({ cls: "kvs-gh-desc", text: desc });
}

/** A quiet explanatory line (used where a control needs a nudge rather than a full description). */
export function hint(parent: HTMLElement, text: string): void {
  parent.createDiv({ cls: "kvs-hint", text });
}

/** A framed "nothing here yet" block with an optional call to action. */
export function emptyState(parent: HTMLElement, icon: string, title: string, desc?: string): HTMLElement {
  const box = parent.createDiv({ cls: "kvs-es" });
  setIcon(box.createDiv({ cls: "kvs-es-icon" }), icon);
  box.createDiv({ cls: "kvs-es-title", text: title });
  if (desc) box.createDiv({ cls: "kvs-es-desc", text: desc });
  return box;
}

/** A native-looking button. */
export function button(parent: HTMLElement, label: string, cta = false): HTMLButtonElement {
  const b = parent.createEl("button", { text: label });
  if (cta) b.addClass("mod-cta");
  return b;
}

/** A small square icon button. */
export function iconButton(parent: HTMLElement, icon: string, tip: string, onClick: () => void): HTMLElement {
  const b = parent.createEl("button", { cls: "clickable-icon kvs-ib" });
  setIcon(b, icon);
  setTooltip(b, tip);
  b.addEventListener("click", onClick);
  return b;
}

// ---------------------------------------------------------------- option cards

export interface OptionCard {
  readonly id: string;
  readonly title: string;
  readonly desc: string;
  readonly icon: string;
  readonly on: boolean;
}

/**
 * A grid of selectable cards — for an option set where each choice deserves an explanation (rather than
 * a column of unlabelled toggles). Clicking a card toggles it.
 */
export function optionCards(parent: HTMLElement, cards: readonly OptionCard[], onToggle: (id: string, on: boolean) => void): void {
  const grid = parent.createDiv({ cls: "kvs-oc-grid" });
  for (const card of cards) {
    const el = grid.createDiv({ cls: "kvs-oc" });
    el.toggleClass("is-on", card.on);
    el.setAttribute("role", "checkbox");
    el.setAttribute("aria-checked", String(card.on));
    el.setAttribute("tabindex", "0");

    const check = el.createDiv({ cls: "kvs-oc-check" });
    if (card.on) setIcon(check, "check");
    setIcon(el.createDiv({ cls: "kvs-oc-icon" }), card.icon);
    const text = el.createDiv({ cls: "kvs-oc-text" });
    text.createDiv({ cls: "kvs-oc-title", text: card.title });
    text.createDiv({ cls: "kvs-oc-desc", text: card.desc });

    const toggle = (): void => onToggle(card.id, !card.on);
    el.addEventListener("click", toggle);
    el.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        toggle();
      }
    });
  }
}

// ---------------------------------------------------------------- chip field

export interface ChipFieldOptions {
  readonly chips: readonly string[];
  readonly emptyLabel: string;
  readonly placeholder: string;
  readonly icon?: string;
  /** Candidates for the dropdown, already filtered of anything selected. */
  readonly suggest: (query: string) => string[];
  readonly onAdd: (value: string) => void;
  readonly onRemove: (value: string) => void;
}

/**
 * A full-width multi-select: selected values as removable chips inside one bordered field, with an
 * inline input that suggests candidates. Reads as a single control instead of a label with a loose
 * list and a stray text box beneath it.
 */
export function chipField(parent: HTMLElement, options: ChipFieldOptions): void {
  const field = parent.createDiv({ cls: "kvs-chips" });
  const box = field.createDiv({ cls: "kvs-chips-box" });

  if (options.chips.length === 0) {
    const empty = box.createSpan({ cls: "kvs-chips-empty" });
    if (options.icon) setIcon(empty.createSpan({ cls: "kvs-chips-empty-ic" }), options.icon);
    empty.createSpan({ text: options.emptyLabel });
  }
  for (const value of options.chips) {
    const chip = box.createSpan({ cls: "kvs-chip" });
    if (options.icon) setIcon(chip.createSpan({ cls: "kvs-chip-ic" }), options.icon);
    chip.createSpan({ cls: "kvs-chip-label", text: value });
    const x = chip.createSpan({ cls: "kvs-chip-x" });
    setIcon(x, "x");
    x.setAttribute("aria-label", `Remove ${value}`);
    x.addEventListener("click", (e) => {
      e.stopPropagation();
      options.onRemove(value);
    });
  }

  const wrap = box.createDiv({ cls: "kvs-chips-input-wrap" });
  const input = wrap.createEl("input", { cls: "kvs-chips-input", type: "text" });
  input.placeholder = options.chips.length === 0 ? options.placeholder : "";
  const menu = wrap.createDiv({ cls: "kvs-chips-menu" });
  menu.hide();

  const refresh = (): void => {
    const found = options.suggest(input.value.trim().toLowerCase()).slice(0, 12);
    menu.empty();
    if (found.length === 0) {
      menu.hide();
      return;
    }
    for (const value of found) {
      const item = menu.createDiv({ cls: "kvs-chips-menu-item" });
      if (options.icon) setIcon(item.createSpan({ cls: "kvs-chips-menu-ic" }), options.icon);
      item.createSpan({ text: value });
      item.addEventListener("mousedown", (e) => {
        e.preventDefault();
        options.onAdd(value);
      });
    }
    menu.show();
  };
  input.addEventListener("focus", refresh);
  input.addEventListener("input", refresh);
  input.addEventListener("blur", () => window.setTimeout(() => menu.hide(), 150));
  box.addEventListener("click", () => input.focus());
}

// ---------------------------------------------------------------- record cards

export interface RecordCardOptions {
  /** Shown at the left of the header — usually the record's position. */
  readonly badge?: string;
  /** Header actions (move, remove). */
  readonly actions?: (bar: HTMLElement) => void;
}

export interface RecordCard {
  /** The header's centre slot — put the record's identity control (e.g. its name input) here. */
  readonly title: HTMLElement;
  /** The body: a responsive grid of compact fields. */
  readonly grid: HTMLElement;
  readonly el: HTMLElement;
}

/**
 * One record (a column, a rollup) as a compact card: an identity header and a grid of small labelled
 * fields — instead of eight full-width label/control rows stacked per record.
 */
export function recordCard(parent: HTMLElement, options: RecordCardOptions = {}): RecordCard {
  const el = parent.createDiv({ cls: "kvs-rec" });
  const head = el.createDiv({ cls: "kvs-rec-head" });
  if (options.badge !== undefined) head.createSpan({ cls: "kvs-rec-badge", text: options.badge });
  const title = head.createDiv({ cls: "kvs-rec-title" });
  const actions = head.createDiv({ cls: "kvs-rec-actions" });
  if (options.actions) options.actions(actions);
  const grid = el.createDiv({ cls: "kvs-rec-grid" });
  return { title, grid, el };
}

/** A compact labelled field inside a record grid (or any grid). Returns the control slot. */
export function miniField(grid: HTMLElement, label: string, opts: { wide?: boolean; hint?: string } = {}): HTMLElement {
  const field = grid.createDiv({ cls: "kvs-mf" });
  if (opts.wide) field.addClass("is-wide");
  const lab = field.createDiv({ cls: "kvs-mf-label", text: label });
  if (opts.hint) setTooltip(lab, opts.hint);
  return field.createDiv({ cls: "kvs-mf-ctl" });
}

/** A `<select>` styled like Obsidian's dropdowns, for use inside record grids. */
export function select(
  parent: HTMLElement,
  choices: readonly { value: string; label: string }[],
  value: string,
  onChange: (value: string) => void,
): HTMLSelectElement {
  const el = parent.createEl("select", { cls: "dropdown" });
  for (const choice of choices) el.createEl("option", { value: choice.value, text: choice.label });
  el.value = value;
  el.addEventListener("change", () => onChange(el.value));
  return el;
}

/** A text input for use inside record grids. */
export function textInput(
  parent: HTMLElement,
  value: string,
  placeholder: string,
  onChange: (value: string) => void,
): HTMLInputElement {
  const el = parent.createEl("input", { type: "text" });
  el.value = value;
  el.placeholder = placeholder;
  el.addEventListener("change", () => onChange(el.value));
  el.addEventListener("blur", () => onChange(el.value));
  return el;
}

/** A checkbox styled as Obsidian's toggle, for use inside record grids. */
export function toggle(parent: HTMLElement, value: boolean, onChange: (value: boolean) => void): HTMLElement {
  const el = parent.createDiv({ cls: "checkbox-container" });
  el.toggleClass("is-enabled", value);
  el.setAttribute("role", "checkbox");
  el.setAttribute("tabindex", "0");
  el.setAttribute("aria-checked", String(value));
  const flip = (): void => {
    const next = !el.hasClass("is-enabled");
    el.toggleClass("is-enabled", next);
    el.setAttribute("aria-checked", String(next));
    onChange(next);
  };
  el.addEventListener("click", flip);
  el.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      flip();
    }
  });
  return el;
}
