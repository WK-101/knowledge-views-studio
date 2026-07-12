import type { KvsAnnotation } from "../../domain/index";

/**
 * Merge annotations from one or more sources, deduping by id (source-independent). When the same
 * annotation appears twice, keep the fuller record and combine comments — so PDF + Zotero collapse
 * into one entry rather than duplicating.
 */
export function mergeAnnotations(...groups: readonly (readonly KvsAnnotation[])[]): KvsAnnotation[] {
  const byId = new Map<string, KvsAnnotation>();
  for (const group of groups) {
    for (const a of group) {
      const existing = byId.get(a.id);
      if (!existing) {
        byId.set(a.id, a);
        continue;
      }
      const comment = [existing.comment, a.comment].map((c) => c.trim()).filter((c) => c !== "");
      byId.set(a.id, {
        ...existing,
        text: existing.text.trim() !== "" ? existing.text : a.text,
        comment: [...new Set(comment)].join("\n\n"),
      });
    }
  }
  return [...byId.values()];
}
