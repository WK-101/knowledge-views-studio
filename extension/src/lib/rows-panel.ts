import type { PageSnapshot } from "../../../shared/extract";
import { findRowCandidates, candidateRowToFields, type RowCandidate } from "../../../shared/extract-rows";
import type { CaptureRequest, SchemaView } from "../../../shared/protocol";
import { BridgeError, capture, loadConnection } from "./bridge-client";
import { queueCapture } from "./queue-store";

/**
 * Capturing many rows from one page.
 *
 * The clearest thing a row-shaped tool can do that a note-shaped one can't. A journal's contents page, a
 * search result, a bibliography or a comparison table is *already* a set of rows; every clipper flattens it
 * into one note because a note is all it can make. Here it goes in as rows, in one write.
 *
 * Nothing is captured without being seen first. The panel shows what it found, how many rows, and the first
 * few of them, because bulk import is exactly where a wrong guess is most expensive to undo — twenty bad
 * rows take far longer to remove than one.
 */

interface Elements {
  readonly host: HTMLElement;
  readonly view: () => SchemaView | null;
  readonly setStatus: (message: string, kind?: "info" | "error" | "ok") => void;
}

const PREVIEW_ROWS = 3;

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

/** Draw a few rows so the shape of what's about to be imported is visible, not just its size. */
function preview(candidate: RowCandidate): HTMLElement {
  const table = node("table", { class: "preview" });
  const head = node("tr");
  for (const header of candidate.headers.slice(0, 4)) head.appendChild(node("th", {}, header));
  table.appendChild(head);

  for (const row of candidate.rows.slice(0, PREVIEW_ROWS)) {
    const tr = node("tr");
    for (const cell of row.slice(0, 4)) {
      tr.appendChild(node("td", {}, cell.length > 40 ? `${cell.slice(0, 40)}…` : cell));
    }
    table.appendChild(tr);
  }
  return table;
}

export function mountRows(snapshot: PageSnapshot, elements: Elements): boolean {
  const { host, view, setStatus } = elements;
  const candidates = findRowCandidates(snapshot);
  host.replaceChildren();
  if (candidates.length === 0) return false;

  const picker = node("select", { id: "rowset" });
  candidates.forEach((candidate, index) => {
    picker.appendChild(
      node("option", { value: String(index) }, `${candidate.label} — ${String(candidate.rows.length)} rows`),
    );
  });

  const field = node("label", { class: "field" });
  field.appendChild(node("span", {}, "Found on this page"));
  field.appendChild(picker);
  host.appendChild(field);

  const previewHost = node("div", { class: "preview-host" });
  host.appendChild(previewHost);

  const button = node("button", { class: "primary", type: "button" }, "Capture these rows");
  host.appendChild(button);

  const draw = (): RowCandidate | null => {
    const candidate = candidates[Number(picker.value)] ?? candidates[0] ?? null;
    previewHost.replaceChildren();
    if (candidate === null) return null;
    previewHost.appendChild(preview(candidate));
    const more = candidate.rows.length - PREVIEW_ROWS;
    if (more > 0) previewHost.appendChild(node("p", { class: "hint" }, `…and ${String(more)} more`));
    button.textContent = `Capture ${String(candidate.rows.length)} rows`;
    return candidate;
  };
  picker.addEventListener("change", () => void draw());
  draw();

  button.addEventListener("click", () => {
    void (async () => {
      const candidate = draw();
      const target = view();
      if (candidate === null || target === null) {
        setStatus("Pick a view to capture into first.", "error");
        return;
      }
      const rows = candidate.rows.map((_, index) => candidateRowToFields(candidate, index));
      const request: CaptureRequest = {
        viewId: target.id,
        fields: [],
        rows,
        ...(snapshot.url !== "" ? { url: snapshot.url } : {}),
      };

      button.setAttribute("disabled", "");
      setStatus(`Saving ${String(rows.length)} rows…`);
      try {
        const connection = await loadConnection();
        const result = await capture(connection, request);
        if (!result.ok) {
          setStatus(result.reason ?? "Couldn't save those rows.", "error");
          button.removeAttribute("disabled");
          return;
        }
        const parts = [`Saved ${String(result.written ?? rows.length)} rows`];
        if (result.createdTable === true) parts.push("(created the table)");
        if (result.duplicate !== undefined) parts.push(`· ${result.duplicate.on}`);
        setStatus(parts.join(" "), "ok");
      } catch (error) {
        if (error instanceof BridgeError && error.offline) {
          await queueCapture(request);
          setStatus("Your vault isn't reachable — saved to send when it is.", "info");
          return;
        }
        setStatus(error instanceof BridgeError ? error.message : "Couldn't save those rows.", "error");
        button.removeAttribute("disabled");
      }
    })();
  });

  return true;
}
