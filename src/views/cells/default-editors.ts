import { TFile } from "obsidian";
import { decodeCellText } from "../../util/markdown";
import { CellEditorRegistry, type CellEditContext } from "./cell-editor";
import { splitTags } from "../../domain/columns/types/tags";
import { RATING_MAX } from "../../domain/columns/types/rating";
import { TagSuggest } from "./tag-suggest";

function once(fn: () => void): () => void {
  let done = false;
  return () => {
    if (done) return;
    done = true;
    fn();
  };
}

/** Single-line input — for short values (number, date) and the select fallback. */
function singleLineEditor(ctx: CellEditContext): void {
  ctx.el.empty();
  const input = ctx.el.createEl("input", { cls: "kvs-cell-input" });
  if (ctx.column.typeId === "number") {
    input.type = "number";
    input.inputMode = "decimal";
  } else {
    input.type = "text";
  }
  input.value = ctx.value;
  window.setTimeout(() => {
    input.focus();
    input.select();
  }, 0);

  const commit = once(() => ctx.commit(input.value));
  const cancel = once(() => ctx.cancel());
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancel();
    }
  });
  input.addEventListener("blur", () => commit());
}

/** Save a pasted image into the vault and insert its embed at the textarea cursor. */
async function insertPastedImage(ctx: CellEditContext, area: HTMLTextAreaElement, file: File, after: () => void): Promise<void> {
  try {
    const source = ctx.sourcePath ?? ctx.app.workspace.getActiveFile()?.path ?? "";
    const ext = (file.name.split(".").pop() || file.type.split("/")[1] || "png").toLowerCase();
    const name = file.name && file.name !== "image.png" ? file.name : `pasted-${Date.now()}.${ext}`;
    const path = await ctx.app.fileManager.getAvailablePathForAttachment(name, source);
    await ctx.app.vault.createBinary(path, await file.arrayBuffer());
    const target = ctx.app.vault.getAbstractFileByPath(path);
    const link = target instanceof TFile ? ctx.app.fileManager.generateMarkdownLink(target, source) : `![[${path.split("/").pop()}]]`;
    const embed = link.startsWith("!") ? link : `!${link}`;
    const pos = area.selectionStart ?? area.value.length;
    area.value = `${area.value.slice(0, pos)}${embed}${area.value.slice(area.selectionEnd ?? pos)}`;
    after();
  } catch {
    // Attaching failed (read-only vault?) — leave the text untouched.
  }
}

function markdownEditor(ctx: CellEditContext): void {
  ctx.el.empty();
  const area = ctx.el.createEl("textarea", { cls: "kvs-cell-input kvs-cell-textarea" });
  area.value = decodeCellText(ctx.value);
  area.rows = 1;
  const autoGrow = (): void => {
    area.setCssStyles({ height: "auto" });
    area.setCssStyles({ height: `${area.scrollHeight}px` });
  };
  window.setTimeout(() => {
    area.focus();
    autoGrow();
  }, 0);
  area.addEventListener("input", autoGrow);
  area.addEventListener("paste", (event) => {
    const items = event.clipboardData?.items;
    let file: File | null = null;
    for (let i = 0; items && i < items.length; i++) {
      const it = items[i]!;
      if (it.type.startsWith("image/")) {
        file = it.getAsFile();
        break;
      }
    }
    if (!file) return;
    event.preventDefault();
    void insertPastedImage(ctx, area, file, autoGrow);
  });

  const commit = once(() => ctx.commit(area.value));
  const cancel = once(() => ctx.cancel());
  area.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancel();
    }
  });
  area.addEventListener("blur", () => commit());
}

/**
 * Wrapping, auto-growing editor for free text. A long value wraps onto multiple lines and the box
 * grows to fit — matching how the cell renders — instead of scrolling off as one continuous line.
 * Enter commits (a Markdown table cell can't hold a literal newline); pasted newlines collapse to
 * spaces on commit.
 */
function wrappingTextEditor(ctx: CellEditContext): void {
  ctx.el.empty();
  const area = ctx.el.createEl("textarea", { cls: "kvs-cell-input kvs-cell-textarea" });
  area.value = decodeCellText(ctx.value);
  area.rows = 1;
  const autoGrow = (): void => {
    area.setCssStyles({ height: "auto" });
    area.setCssStyles({ height: `${area.scrollHeight}px` });
  };
  window.setTimeout(() => {
    area.focus();
    area.setSelectionRange(area.value.length, area.value.length);
    autoGrow();
  }, 0);
  area.addEventListener("input", autoGrow);

  const commit = once(() => ctx.commit(area.value.replace(/\r?\n+/g, " ")));
  const cancel = once(() => ctx.cancel());
  area.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault(); // Enter commits; the box already wraps long text on its own
      commit();
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancel();
    }
  });
  area.addEventListener("blur", () => commit());
}

function tagsEditor(ctx: CellEditContext): void {
  const root = ctx.el;
  root.empty();
  root.addClass("kvs-tags-editor");
  let tags = splitTags(ctx.value);

  const chips = root.createSpan({ cls: "kvs-tags-chips" });
  const input = root.createEl("input", { cls: "kvs-tags-input" });
  input.type = "text";

  const addTag = (value: string): void => {
    const tag = value.replace(/^#/, "").trim();
    if (tag && !tags.includes(tag)) {
      tags = [...tags, tag];
      renderChips();
    }
  };
  const suggest = new TagSuggest(ctx.app, input, () => tags, (tag) => {
    addTag(tag);
    input.focus();
  });
  void suggest;

  const commit = once(() => ctx.commit(tags.join(", ")));
  const cancel = once(() => ctx.cancel());

  const renderChips = (): void => {
    chips.empty();
    tags.forEach((tag, index) => {
      const chip = chips.createSpan({ cls: "kvs-tag-token", text: tag });
      const remove = chip.createSpan({ cls: "kvs-tag-token-x", text: "×" });
      remove.addEventListener("mousedown", (event) => {
        event.preventDefault();
        tags = tags.filter((_, i) => i !== index);
        renderChips();
        input.focus();
      });
    });
    input.placeholder = tags.length === 0 ? "add tags…" : "";
  };

  const addCurrent = (): void => {
    const value = input.value.trim();
    input.value = "";
    addTag(value);
  };

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      if (input.value.trim()) addCurrent();
      else commit();
    } else if (event.key === ",") {
      event.preventDefault();
      addCurrent();
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancel();
    } else if (event.key === "Backspace" && input.value === "" && tags.length > 0) {
      event.preventDefault();
      tags = tags.slice(0, -1);
      renderChips();
    }
  });
  input.addEventListener("blur", () => {
    window.setTimeout(() => {
      const active = document.activeElement;
      if (root.contains(active)) return;
      if (active instanceof HTMLElement && active.closest(".suggestion-container")) return;
      commit();
    }, 0);
  });

  renderChips();
  window.setTimeout(() => input.focus(), 0);
}

function selectEditor(ctx: CellEditContext): void {
  const options = ctx.column.options;
  if (!options || options.length === 0) {
    // No predefined options: offer existing values (e.g. statuses already in use) as autocomplete.
    if ((ctx.suggestions ?? []).length > 0) {
      listEditor(ctx);
      return;
    }
    singleLineEditor(ctx);
    return;
  }
  ctx.el.empty();
  const select = ctx.el.createEl("select", { cls: "dropdown kvs-cell-input" });
  select.createEl("option", { text: "—", value: "" });
  const current = ctx.value.trim();
  for (const option of options) {
    const el = select.createEl("option", { text: option.label ?? option.value, value: option.value });
    if (option.value === current) el.selected = true;
  }
  window.setTimeout(() => select.focus(), 0);

  const commit = once(() => ctx.commit(select.value));
  select.addEventListener("change", () => commit());
  select.addEventListener("keydown", (event) => {
    if (event.key === "Escape") ctx.cancel();
  });
  select.addEventListener("blur", () => ctx.cancel());
}

function ratingEditor(ctx: CellEditContext): void {
  ctx.el.empty();
  const wrap = ctx.el.createDiv({ cls: "kvs-rating-editor" });
  const max = RATING_MAX;
  let current = Math.max(0, Math.min(max, Math.round(Number(ctx.value) || 0)));
  const stars: HTMLElement[] = [];
  const paint = (n: number): void => stars.forEach((star, i) => star.toggleClass("is-on", i < n));
  for (let i = 1; i <= max; i++) {
    const star = wrap.createSpan({ cls: "kvs-rating-star", text: "★" });
    star.addEventListener("mouseenter", () => paint(i));
    star.addEventListener("mouseleave", () => paint(current));
    star.addEventListener("click", () => {
      current = current === i ? i - 1 : i;
      paint(current);
      ctx.commit(String(current));
    });
    stars.push(star);
  }
  paint(current);
  const clear = wrap.createEl("button", { cls: "kvs-rating-clear", text: "Clear" });
  clear.addEventListener("click", () => ctx.commit("0"));
}

/** Inline editor for list/theme cells: a text input with a datalist of existing values, so themes
 *  stay consistent as you type. Multi-value entry stays comma-separated (as `list` expects). */
function listEditor(ctx: CellEditContext): void {
  ctx.el.empty();
  const input = ctx.el.createEl("input", { cls: "kvs-cell-input" });
  input.value = ctx.value;
  const suggestions = ctx.suggestions ?? [];
  if (suggestions.length > 0) {
    const id = `kvs-dl-${Math.random().toString(36).slice(2)}`;
    const dl = ctx.el.createEl("datalist");
    dl.id = id;
    for (const v of suggestions) dl.createEl("option", { value: v });
    input.setAttr("list", id);
  }
  window.setTimeout(() => input.focus(), 0);
  const commit = once(() => ctx.commit(input.value));
  input.addEventListener("blur", () => commit());
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape") ctx.cancel();
  });
}

/** Editor for date cells: a native date picker plus a "Today" shortcut (handy for a "read on" date). */
function dateEditor(ctx: CellEditContext): void {
  ctx.el.empty();
  const wrap = ctx.el.createDiv({ cls: "kvs-date-editor" });
  const input = wrap.createEl("input", { cls: "kvs-cell-input" });
  input.type = "date";
  const iso = /^\d{4}-\d{2}-\d{2}/.test(ctx.value.trim())
    ? ctx.value.trim().slice(0, 10)
    : (() => {
        const d = new Date(ctx.value.trim());
        return Number.isNaN(d.getTime()) ? "" : d.toISOString().slice(0, 10);
      })();
  if (iso) input.value = iso;
  window.setTimeout(() => input.focus(), 0);
  const commit = once(() => ctx.commit(input.value));
  input.addEventListener("change", () => commit());
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape") ctx.cancel();
  });
  const today = wrap.createEl("button", { cls: "kvs-date-today", text: "Today" });
  today.addEventListener("mousedown", (event) => {
    event.preventDefault();
    input.value = new Date().toISOString().slice(0, 10);
    ctx.commit(input.value);
  });
}

export function createDefaultCellEditorRegistry(): CellEditorRegistry {
  const registry = new CellEditorRegistry();
  registry.register({ typeId: "text", edit: wrappingTextEditor }, true);
  registry.register({ typeId: "markdown", edit: markdownEditor });
  registry.register({ typeId: "select", edit: selectEditor });
  registry.register({ typeId: "tags", edit: tagsEditor });
  registry.register({ typeId: "list", edit: listEditor });
  registry.register({ typeId: "number", edit: singleLineEditor });
  registry.register({ typeId: "date", edit: dateEditor });
  registry.register({ typeId: "rating", edit: ratingEditor });
  return registry;
}
