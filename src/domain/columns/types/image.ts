import type { ColumnType } from "../column-type";
import { extractImageEmbeds } from "../../../util/markdown";

export const IMAGE: ColumnType = {
  id: "image",
  label: "Image",
  operators: ["contains", "not-contains", "is-empty", "is-not-empty"],
  isEmpty: (raw) => extractImageEmbeds(raw).length === 0 && String(raw ?? "").trim() === "",
  toComparable: (raw) => ({ kind: "string", value: String(raw ?? "").trim().toLowerCase() }),
  toPlainText: (raw) => {
    const embeds = extractImageEmbeds(raw);
    return embeds.length > 0 ? embeds.join(" ") : String(raw ?? "").trim();
  },
  validate: () => null,
};
