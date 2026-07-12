import { Notice } from "obsidian";
import type { Row, RowProvenance } from "../domain/index";
import type { DataService, FileSnapshot, UndoManager, WriterService } from "../services/index";

export interface EditingHandlers {
  readonly onEditCell: (row: Row, column: string, value: string) => void;
  readonly onDeleteRow: (row: Row) => void;
  readonly onAddRow: (row: Row) => void;
  /** Append a copy of a row (its values) as a new row — quick "same again" entry. */
  readonly onDuplicateRow: (row: Row) => void;
  readonly onBulkEdit: (rows: readonly Row[], column: string, value: string) => void;
  readonly onBulkDelete: (rows: readonly Row[]) => void;
}

export interface EditingDeps {
  readonly dataService: DataService;
  readonly writer: WriterService;
  readonly undo: UndoManager;
}

/**
 * Build the write-back handlers a host hands to a view. Each one writes through
 * the WriterService, surfaces failures as a Notice, then invalidates the file's
 * cache and re-renders — so an edit shows up immediately, independent of the
 * auto-refresh setting.
 */
export function createEditingHandlers(
  deps: EditingDeps,
  rerender: () => void,
  /** Optional initial cell values for a newly added row (column defaults, dynamic tokens resolved). */
  newRowValues?: () => Readonly<Record<string, string>>,
  /** When provided, single-cell edits are routed here (coalesced background save) instead of written
   *  immediately — giving instant, batched, native-feeling edits. */
  queueCellEdit?: (row: Row, column: string, value: string) => void,
  /** Resolve which table a new row goes into (per-view target); defaults to the clicked row's table. */
  resolveAppendTarget?: (clickedRow: Row) => RowProvenance,
): EditingHandlers {
  const after = (path: string): void => {
    deps.dataService.invalidate(path);
    rerender();
  };

  const pushUndo = (label: string, snapshot: FileSnapshot): void => {
    deps.undo.push({
      label,
      undo: async () => {
        await deps.writer.restore(snapshot);
        for (const path of snapshot.keys()) deps.dataService.invalidate(path);
        rerender();
      },
    });
  };

  // Run a write handler, catching *thrown* errors (a graceful failure is reported via its own result
  // object). Without this, a rejected write (file locked, deleted mid-edit, unexpected exception)
  // would be an unhandled rejection: the edit would silently do nothing with no notice or rollback.
  const safe = (action: string, fn: () => Promise<void>): void => {
    void fn().catch((error: unknown) => {
      console.error(`[KVS] ${action} failed:`, error);
      new Notice(`Couldn't ${action}: ${error instanceof Error ? error.message : "unexpected error"}`);
    });
  };

  return {
    onEditCell: (row, column, value) => {
      if (queueCellEdit) {
        queueCellEdit(row, column, value); // coalesced background save + optimistic overlay
        return;
      }
      safe(`update "${column}"`, async () => {
        const snapshot = await deps.writer.snapshot([row.provenance.filePath]);
        const result = await deps.writer.editCells([{ provenance: row.provenance, column, value }]);
        if (result.failures.length > 0) {
          new Notice(`Couldn't update "${column}": ${result.failures[0]?.reason ?? "unknown error"}`);
        }
        if (result.applied > 0) pushUndo(`Edit "${column}"`, snapshot);
        after(row.provenance.filePath);
      });
    },
    onDeleteRow: (row) => {
      safe("delete row", async () => {
        const snapshot = await deps.writer.snapshot([row.provenance.filePath]);
        const result = await deps.writer.deleteRows([row.provenance]);
        if (!result.ok) new Notice(`Couldn't delete row: ${result.reason ?? "unknown error"}`);
        else pushUndo("Delete row", snapshot);
        after(row.provenance.filePath);
      });
    },
    onAddRow: (row) => {
      safe("add row", async () => {
        const target = resolveAppendTarget ? resolveAppendTarget(row) : row.provenance;
        const snapshot = await deps.writer.snapshot([target.filePath]);
        const result = await deps.writer.appendRow(target, newRowValues ? newRowValues() : {});
        if (!result.ok) new Notice(`Couldn't add row: ${result.reason ?? "unknown error"}`);
        else pushUndo("Add row", snapshot);
        after(target.filePath);
      });
    },
    onDuplicateRow: (row) => {
      safe("duplicate row", async () => {
        const snapshot = await deps.writer.snapshot([row.provenance.filePath]);
        // Copy the row's own cell values; only real table headers are written (derived cells ignored).
        const result = await deps.writer.appendRow(row.provenance, { ...row.cells });
        if (!result.ok) new Notice(`Couldn't duplicate row: ${result.reason ?? "unknown error"}`);
        else pushUndo("Duplicate row", snapshot);
        after(row.provenance.filePath);
      });
    },
    onBulkEdit: (rows, column, value) => {
      safe(`bulk update "${column}"`, async () => {
        const paths = [...new Set(rows.map((row) => row.provenance.filePath))];
        const snapshot = await deps.writer.snapshot(paths);
        const edits = rows.map((row) => ({ provenance: row.provenance, column, value }));
        const result = await deps.writer.editCells(edits);
        if (result.failures.length > 0) {
          new Notice(`Bulk update: ${result.failures.length} cell(s) could not be updated.`);
        }
        if (result.applied > 0) pushUndo(`Bulk edit "${column}" (${result.applied})`, snapshot);
        for (const path of paths) deps.dataService.invalidate(path);
        rerender();
      });
    },
    onBulkDelete: (rows) => {
      safe("delete rows", async () => {
        // Deletion is supported for Markdown notes and Excel workbooks; other sources stay read-only.
        const deletable = rows.filter((r) => {
          const p = r.provenance.filePath.toLowerCase();
          return p.endsWith(".md") || p.endsWith(".xlsx");
        });
        const skipped = rows.length - deletable.length;
        if (deletable.length === 0) {
          new Notice("These rows come from a read-only source and can't be deleted here.");
          return;
        }
        const paths = [...new Set(deletable.map((r) => r.provenance.filePath))];
        const snapshot = await deps.writer.snapshot(paths);

        const byFile = new Map<string, RowProvenance[]>();
        for (const r of deletable) {
          const list = byFile.get(r.provenance.filePath);
          if (list) list.push(r.provenance);
          else byFile.set(r.provenance.filePath, [r.provenance]);
        }

        let deleted = 0;
        let failedFiles = 0;
        for (const [, provs] of byFile) {
          const result = await deps.writer.deleteRows(provs);
          if (result.ok) deleted += provs.length;
          else failedFiles++;
        }
        if (deleted > 0) pushUndo(`Delete ${deleted} row(s)`, snapshot);
        for (const path of paths) deps.dataService.invalidate(path);

        const parts: string[] = [];
        if (deleted > 0) parts.push(`Deleted ${deleted} row${deleted === 1 ? "" : "s"}`);
        if (failedFiles > 0) parts.push(`${failedFiles} file(s) couldn't be updated`);
        if (skipped > 0) parts.push(`${skipped} read-only row(s) skipped`);
        if (parts.length > 0) new Notice(parts.join(" · "));
        rerender();
      });
    },
  };
}
