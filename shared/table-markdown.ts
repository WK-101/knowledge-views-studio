/**
 * Turning a parsed table into a GitHub-flavoured Markdown table.
 *
 * This is the "copy" half of the on-page table action: what lands on the clipboard when someone copies a
 * table they're hovering. It's pure and lives here so it can be tested without a DOM, and so the content
 * script stays thin.
 *
 * The rules are the ones that keep a pasted table intact: pipes inside cells are escaped (an unescaped pipe
 * would start a new column), newlines within a cell are flattened to spaces (a literal newline breaks the
 * row), every row is padded or trimmed to the header's column count (a ragged row misaligns everything
 * after it), and an empty input yields an empty string rather than a header with no body.
 */

function cell(text: string): string {
  return text.replace(/\r?\n/g, " ").replace(/\s+/g, " ").replace(/\|/g, "\\|").trim();
}

export function tableToMarkdown(headers: readonly string[], rows: readonly (readonly string[])[]): string {
  const width = headers.length;
  if (width === 0) return "";

  const headerLine = `| ${headers.map(cell).join(" | ")} |`;
  const divider = `| ${headers.map(() => "---").join(" | ")} |`;

  const bodyLines = rows.map((row) => {
    // Pad short rows, trim long ones, so every line has exactly `width` columns.
    const cells: string[] = [];
    for (let i = 0; i < width; i++) cells.push(cell(row[i] ?? ""));
    return `| ${cells.join(" | ")} |`;
  });

  return [headerLine, divider, ...bodyLines].join("\n");
}
