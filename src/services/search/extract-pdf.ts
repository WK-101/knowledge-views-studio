import { loadPdfjs } from "../annotations/pdf-annotation-store";

/** Extract the full text of a PDF, one section per page (for per-page indexing + jump-to-page). Async
 *  because it drives the pdf.js worker. */
export async function extractPdfText(bytes: ArrayBuffer): Promise<{ location: string; text: string }[]> {
  const pdfjs = await loadPdfjs();
  // pdf.js detaches the buffer it's given — hand it a private copy.
  const data = new Uint8Array(bytes.slice(0));
  const doc = await pdfjs.getDocument({ data, isEvalSupported: false, useSystemFonts: false }).promise;
  const out: { location: string; text: string }[] = [];
  try {
    for (let p = 1; p <= doc.numPages; p++) {
      const page = await doc.getPage(p);
      const content = await page.getTextContent();
      const text = content.items
        .map((it) => ((it as { str?: string }).str ?? ""))
        .join(" ")
        .replace(/\s+/g, " ")
        .trim();
      if (text !== "") out.push({ location: `p.${p}`, text });
    }
  } finally {
    await doc.destroy();
  }
  return out;
}
