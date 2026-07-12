import type { RowProvenance } from "../../domain/index";
import type { VaultGateway } from "../ports/vault-gateway";
import {
  appendRow,
  applyCellEdits,
  deleteRows,
  type CellEdit,
  type CellWriteResult,
  type RowWriteResult,
} from "./source-writer";
import { applyFrontmatterEdits } from "./frontmatter-writer";
import { applyTaskEdits } from "./task-writer";
import { applyInlineFieldEdits } from "./inline-field-writer";
import { FRONTMATTER_EXTRACTOR_ID, TASK_EXTRACTOR_ID, INLINE_EXTRACTOR_ID } from "../../domain/index";
import { appendXlsxRows, applyXlsxCellEdits, deleteXlsxRows, XLSX_EXTRACTOR_ID } from "../office/index";

/** A captured file state for undo — text for notes, raw bytes for binary sources like `.xlsx`. */
export type FileSnapshot = ReadonlyMap<string, { readonly text: string } | { readonly bytes: Uint8Array }>;

const isXlsxPath = (path: string): boolean => path.toLowerCase().endsWith(".xlsx");

/** Two-digit zero pad. */
const p2 = (n: number): string => (n < 10 ? `0${n}` : String(n));

export interface WriterConfig {
  /** Whether to back up an Excel workbook before editing it (default on). */
  readonly excelBackup: () => boolean;
}

/** Route a file's edits to the writer for their source type (table, frontmatter, …). */
function writeByExtractor(extractor: string, content: string, edits: readonly CellEdit[]): CellWriteResult {
  if (extractor === FRONTMATTER_EXTRACTOR_ID) return applyFrontmatterEdits(content, edits);
  if (extractor === TASK_EXTRACTOR_ID) return applyTaskEdits(content, edits);
  if (extractor === INLINE_EXTRACTOR_ID) return applyInlineFieldEdits(content, edits);
  return applyCellEdits(content, edits);
}


/**
 * Orchestrates write-back to the vault. All mutations go through the gateway's
 * atomic `process`, so the pure writer runs against the freshest content under
 * the host's file lock. Edits are grouped by file, enabling cross-note bulk
 * edits in as many file writes as there are affected files — and no more.
 */
export class WriterService {
  constructor(
    private readonly gateway: VaultGateway,
    private readonly config: WriterConfig = { excelBackup: () => true },
  ) {}

  /**
   * Before the day's first KVS edit to an Excel file, copy it verbatim into `_kvs-backups/` so the
   * pre-edit workbook is always recoverable. One backup per file per day keeps this from cluttering
   * the vault, while still guaranteeing a safe restore point for each editing day.
   */
  private async backupXlsx(path: string, originalBytes: Uint8Array): Promise<void> {
    if (!this.config.excelBackup()) return;
    const now = new Date();
    const day = `${now.getFullYear()}-${p2(now.getMonth() + 1)}-${p2(now.getDate())}`;
    const flat = path.replace(/[\\/]/g, "__");
    const backupPath = `_kvs-backups/${flat}.${day}.xlsx`;
    try {
      if (await this.gateway.exists(backupPath)) return; // already backed up today
      await this.gateway.ensureFolder("_kvs-backups");
      await this.gateway.writeBinary(backupPath, originalBytes);
    } catch (error) {
      console.error("[KVS] Excel backup failed:", error);
    }
  }

  async editCells(edits: readonly CellEdit[]): Promise<CellWriteResult> {
    const byFile = new Map<string, CellEdit[]>();
    for (const edit of edits) {
      const list = byFile.get(edit.provenance.filePath);
      if (list) list.push(edit);
      else byFile.set(edit.provenance.filePath, [edit]);
    }

    let applied = 0;
    const failures: CellWriteResult["failures"] = [];
    for (const [path, fileEdits] of byFile) {
      // Excel sources are binary: rewrite cells in the workbook and save bytes, rather than text.
      if (fileEdits.every((e) => e.provenance.extractor === XLSX_EXTRACTOR_ID)) {
        try {
          const bytes = new Uint8Array(await this.gateway.readBinary(path));
          await this.backupXlsx(path, bytes);
          const result = applyXlsxCellEdits(
            bytes,
            fileEdits.map((e) => ({
              sheet: String(e.provenance.locator.sheet ?? ""),
              row: Number(e.provenance.locator.row ?? 0),
              headerRow: Number(e.provenance.locator.headerRow ?? 0),
              column: e.column,
              value: e.value,
            })),
          );
          if (result.applied > 0) await this.gateway.processBinary(path, () => result.bytes);
          applied += result.applied;
          for (let i = 0; i < result.failed; i++) {
            failures.push({ provenance: fileEdits[0]!.provenance, reason: "Couldn't locate that cell in the workbook." });
          }
        } catch (error) {
          failures.push({
            provenance: fileEdits[0]!.provenance,
            reason: error instanceof Error ? error.message : "Excel write failed.",
          });
        }
        continue;
      }

      // A file can hold rows from several extractors; apply each source's writer in
      // turn. None of these writers change line counts, so positions stay valid.
      const byExtractor = new Map<string, CellEdit[]>();
      for (const edit of fileEdits) {
        const list = byExtractor.get(edit.provenance.extractor);
        if (list) list.push(edit);
        else byExtractor.set(edit.provenance.extractor, [edit]);
      }
      await this.gateway.process(path, (content) => {
        let current = content;
        for (const [extractor, extractorEdits] of byExtractor) {
          const result = writeByExtractor(extractor, current, extractorEdits);
          current = result.content;
          applied += result.applied;
          failures.push(...result.failures);
        }
        return current;
      });
    }
    return { content: "", applied, failures };
  }

  async deleteRows(provenances: readonly RowProvenance[]): Promise<RowWriteResult> {
    const path = provenances[0]?.filePath;
    if (!path) return { content: "", ok: false, reason: "No row selected." };
    if (isXlsxPath(path)) {
      const same = provenances.filter((p) => p.filePath === path);
      try {
        const bytes = new Uint8Array(await this.gateway.readBinary(path));
        await this.backupXlsx(path, bytes);
        const result = deleteXlsxRows(
          bytes,
          same.map((p) => ({ sheet: String(p.locator.sheet ?? ""), row: Number(p.locator.row ?? 0) })),
        );
        if (result.applied === 0) {
          return { content: "", ok: false, reason: "Couldn't delete those rows from the workbook." };
        }
        await this.gateway.processBinary(path, () => result.bytes);
        return { content: "", ok: true };
      } catch (error) {
        return { content: "", ok: false, reason: error instanceof Error ? error.message : "Excel delete failed." };
      }
    }
    // Rows for one file at a time (delete is scoped to the reference file).
    const sameFile = provenances.filter((p) => p.filePath === path);
    let result: RowWriteResult = { content: "", ok: true };
    await this.gateway.process(path, (content) => {
      result = deleteRows(content, sameFile);
      return result.ok ? result.content : content;
    });
    return result;
  }

  /** Capture each affected file's state — text for notes, bytes for `.xlsx` — as an undo point. */
  async snapshot(paths: readonly string[]): Promise<FileSnapshot> {
    const snap = new Map<string, { text: string } | { bytes: Uint8Array }>();
    for (const path of new Set(paths)) {
      if (isXlsxPath(path)) snap.set(path, { bytes: new Uint8Array(await this.gateway.readBinary(path)) });
      else snap.set(path, { text: await this.gateway.read(path) });
    }
    return snap;
  }

  /** Restore files to a previously-captured state (used by undo). */
  async restore(snapshot: FileSnapshot): Promise<void> {
    for (const [path, entry] of snapshot) {
      if ("bytes" in entry) await this.gateway.processBinary(path, () => entry.bytes);
      else await this.gateway.process(path, () => entry.text);
    }
  }

  async appendRow(reference: RowProvenance, values: Readonly<Record<string, string>>): Promise<RowWriteResult> {
    if (isXlsxPath(reference.filePath)) {
      try {
        const bytes = new Uint8Array(await this.gateway.readBinary(reference.filePath));
        await this.backupXlsx(reference.filePath, bytes);
        const result = appendXlsxRows(bytes, [
          {
            sheet: String(reference.locator.sheet ?? ""),
            headerRow: Number(reference.locator.headerRow ?? 0),
            values,
          },
        ]);
        if (result.applied === 0) return { content: "", ok: false, reason: "Couldn't add the row to the workbook." };
        await this.gateway.processBinary(reference.filePath, () => result.bytes);
        return { content: "", ok: true };
      } catch (error) {
        return { content: "", ok: false, reason: error instanceof Error ? error.message : "Excel append failed." };
      }
    }
    let result: RowWriteResult = { content: "", ok: true };
    await this.gateway.process(reference.filePath, (content) => {
      result = appendRow(content, reference, values);
      return result.ok ? result.content : content;
    });
    return result;
  }
}
