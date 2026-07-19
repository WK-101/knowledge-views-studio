import type { RowsRow, SchemaColumn, SchemaResponse, SchemaView } from "../../../shared/protocol";
import { BridgeError, loadConnection, obsidianLink, rows as fetchRows, update } from "./bridge-client";

/**
 * Your views, in the sidebar.
 *
 * The point at which the companion stops being about the page in front of you and becomes a way into the
 * vault itself. A reading queue, a paper list, a backlog — visible and workable while you browse, without
 * switching applications to tick something off.
 *
 * Editing here goes through exactly the same path as the popup's update tab, which means the same
 * safeguards: an opaque row handle that is matched rather than dereferenced, and columns the row doesn't own
 * refused by the vault. Those columns are shown greyed rather than hidden, because a value you can see but
 * not change is information, while one that silently vanished is confusing.
 */

interface Elements {
  readonly host: HTMLElement;
  readonly schema: () => SchemaResponse | null;
  readonly setStatus: (message: string, kind?: "info" | "error" | "ok") => void;
}

const PAGE_SIZE = 25;

function node<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (text !== undefined) el.textContent = text;
  return el;
}

/**
 * Which columns to show, and in what order.
 *
 * A sidebar is narrow, and a view can have twenty columns. What identifies a row comes first, then the
 * things you act on — status, priority, dates — and long prose is left out entirely, since a paragraph
 * rendered into a 300-pixel column helps nobody.
 */
export function displayColumns(columns: readonly SchemaColumn[], limit = 4): SchemaColumn[] {
  const score = (column: SchemaColumn): number => {
    if (column.role === "title") return 0;
    if (column.name.toLowerCase() === "title" || column.name.toLowerCase() === "name") return 1;
    if (column.role === "status" || column.role === "priority") return 2;
    if (column.options !== undefined && column.options.length > 0) return 3;
    if (column.role === "date" || column.typeId === "date") return 4;
    if (column.typeId === "markdown") return 9;
    return 5;
  };
  return [...columns]
    .filter((c) => c.typeId !== "markdown")
    .sort((a, b) => score(a) - score(b))
    .slice(0, limit);
}

export function mountDashboard(elements: Elements): void {
  const { host, schema, setStatus } = elements;
  host.replaceChildren();

  const all = schema()?.views ?? [];
  if (all.length === 0) {
    host.appendChild(node("p", { class: "hint" }, "No views are shared with the companion yet."));
    return;
  }

  const picker = node("select", { id: "dash-view" });
  for (const view of all) picker.appendChild(node("option", { value: view.id }, view.name));
  const pickerField = node("label", { class: "field" });
  pickerField.appendChild(node("span", {}, "View"));
  pickerField.appendChild(picker);
  host.appendChild(pickerField);

  const filter = node("input", { type: "search", placeholder: "Filter these rows…" });
  host.appendChild(filter);

  const body = node("div", { class: "dash-body" });
  host.appendChild(body);

  const footer = node("div", { class: "dash-foot" });
  const prev = node("button", { type: "button" }, "Previous");
  const pageLabel = node("span", { class: "hint" }, "");
  const next = node("button", { type: "button" }, "Next");
  footer.append(prev, pageLabel, next);
  host.appendChild(footer);

  let page = 1;
  let token = 0;

  const draw = (): void => {
    const view = all.find((v) => v.id === picker.value) ?? all[0];
    if (view === undefined) return;
    const mine = ++token;
    body.replaceChildren(node("p", { class: "hint" }, "Loading…"));

    void (async () => {
      try {
        const connection = await loadConnection();
        const result = await fetchRows(connection, {
          viewId: view.id,
          page,
          pageSize: PAGE_SIZE,
          ...(filter.value.trim() !== "" ? { query: filter.value.trim() } : {}),
        });
        if (mine !== token) return; // A later request already answered; this one is stale.

        body.replaceChildren();
        if (result.rows.length === 0) {
          body.appendChild(node("p", { class: "hint" }, "Nothing here."));
        } else {
          const columns = displayColumns(result.columns);
          for (const row of result.rows) body.appendChild(rowCard(row, columns, view, setStatus, schema));
        }
        const last = Math.max(1, Math.ceil(result.total / result.pageSize));
        pageLabel.textContent = `${String(result.total)} rows · page ${String(result.page)} of ${String(last)}`;
        prev.toggleAttribute("disabled", result.page <= 1);
        next.toggleAttribute("disabled", result.page >= last);
      } catch (error) {
        if (mine !== token) return;
        body.replaceChildren(
          node("p", { class: "hint" }, error instanceof BridgeError ? error.message : "Couldn't read that view."),
        );
      }
    })();
  };

  picker.addEventListener("change", () => {
    page = 1;
    draw();
  });
  let debounce: number | undefined;
  filter.addEventListener("input", () => {
    window.clearTimeout(debounce);
    debounce = window.setTimeout(() => {
      page = 1;
      draw();
    }, 250);
  });
  prev.addEventListener("click", () => {
    if (page > 1) {
      page--;
      draw();
    }
  });
  next.addEventListener("click", () => {
    page++;
    draw();
  });
  draw();
}

/** One row, with its editable choices inline so a status can be changed without leaving the list. */
function rowCard(
  row: RowsRow,
  columns: readonly SchemaColumn[],
  view: SchemaView,
  setStatus: Elements["setStatus"],
  schema: Elements["schema"],
): HTMLElement {
  const card = node("div", { class: "dash-row" });
  const readOnly = new Set((row.readOnly ?? []).map((c) => c.toLowerCase()));

  const first = columns[0];
  const titleText = first === undefined ? "" : (row.cells[first.name] ?? "");
  const heading = node("div", { class: "dash-title" }, titleText === "" ? "(untitled)" : titleText);
  card.appendChild(heading);

  const vault = schema()?.vault ?? "";
  const urlValue = Object.entries(row.cells).find(([key]) => key.toLowerCase() === "url")?.[1] ?? "";
  if (urlValue !== "" && /^https?:/i.test(urlValue)) {
    heading.appendChild(
      node("a", { href: urlValue, target: "_blank", rel: "noreferrer", class: "dash-open", title: urlValue }, "↗"),
    );
  }
  void vault;
  void obsidianLink;

  const meta = node("div", { class: "dash-meta" });
  for (const column of columns.slice(1)) {
    const value = row.cells[column.name] ?? "";
    const locked = readOnly.has(column.name.toLowerCase());

    if (column.options !== undefined && column.options.length > 0 && !locked) {
      const select = node("select", { class: "dash-choice", title: column.name });
      select.appendChild(node("option", { value: "" }, `${column.name}: —`));
      for (const option of column.options) {
        const el = node("option", { value: option }, option);
        if (option === value) el.setAttribute("selected", "");
        select.appendChild(el);
      }
      select.addEventListener("change", () => {
        void (async () => {
          const chosen = select.value;
          if (chosen === "") return;
          try {
            const connection = await loadConnection();
            const result = await update(connection, {
              viewId: view.id,
              rowRef: row.rowRef,
              values: [{ key: column.name, value: chosen }],
            });
            setStatus(
              result.ok ? `${column.name} set to ${chosen}` : (result.reason ?? "Couldn't change that."),
              result.ok ? "ok" : "error",
            );
          } catch (error) {
            setStatus(error instanceof BridgeError ? error.message : "Couldn't change that.", "error");
          }
        })();
      });
      meta.appendChild(select);
      continue;
    }

    if (value === "") continue;
    // Shown but not editable: a value you can see and can't change is information; one that vanished is not.
    meta.appendChild(node("span", { class: `dash-tag${locked ? " is-locked" : ""}`, title: column.name }, value));
  }
  if (meta.childNodes.length > 0) card.appendChild(meta);
  return card;
}
