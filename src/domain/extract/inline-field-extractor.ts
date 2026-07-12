import type { Row, RowProvenance } from "../model";
import type { ExtractionInput, SourceExtractor } from "./extractor";
import { fnv1a } from "../../util/hash";

export const INLINE_EXTRACTOR_ID = "inline";

const LINE_FIELD = /^\s*(?:[-*+]\s+)?([A-Za-z0-9_][\w -]*?)::\s*(.*?)\s*$/;
const BRACKET_FIELD = /[[(]([A-Za-z0-9_][\w -]*?)::\s*([^\])]*?)[\])]/g;

/**
 * One row per note built from Dataview-style inline fields — `key:: value` on its
 * own line, or bracketed `[key:: value]` / `(key:: value)` inside prose. First
 * definition of each key wins; edits write back to that occurrence.
 */
export const inlineFieldExtractor: SourceExtractor = {
  id: INLINE_EXTRACTOR_ID,
  label: "Inline fields (key:: value, one row per note)",
  extract({ file, content }: ExtractionInput): Row[] {
    const cells: Record<string, string> = {};
    const order: string[] = [];
    const add = (rawKey: string, rawVal: string): void => {
      const key = rawKey.trim();
      if (key === "" || key in cells) return;
      cells[key] = rawVal.trim();
      order.push(key);
    };

    let match: RegExpExecArray | null;
    const bracket = new RegExp(BRACKET_FIELD);
    while ((match = bracket.exec(content)) !== null) add(match[1] ?? "", match[2] ?? "");

    for (const line of content.split(/\r?\n/)) {
      if (line.trimStart().startsWith("[") || line.trimStart().startsWith("(")) continue;
      const lm = line.match(LINE_FIELD);
      if (lm) add(lm[1] ?? "", lm[2] ?? "");
    }

    if (order.length === 0) return [];
    const provenance: RowProvenance = {
      filePath: file.filePath,
      extractor: INLINE_EXTRACTOR_ID,
      locator: {},
      fingerprint: fnv1a(order.map((k) => `${k}::${cells[k]}`).join("\n")),
    };
    return [{ cells, file, provenance }];
  },
};
