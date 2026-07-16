import { App, Modal, setIcon } from "obsidian";

export interface WelcomeActions {
  /** Build a view from the table in the user's current note. */
  readonly onUseNote: () => void;
  /** Open the starter-template picker. */
  readonly onTemplate: () => void;
  /** Create an empty view and open the dashboard. */
  readonly onBlank: () => void;
  /** Open the search view. */
  readonly onSearch: () => void;
  /** Open the quick-launcher modal (jump to a note). */
  readonly onQuickSearch: () => void;
  /** Whether the Academic Research kit is switched on (its step is only shown then). */
  readonly academicKit: boolean;
}

interface Step {
  readonly title: string;
  readonly lead: string;
  readonly render: (el: HTMLElement) => void;
}

/**
 * A guided first run: one idea per screen, in the order a new person actually meets them — get a view on
 * screen, then look at it differently, then tell it what a row is, then find things (and, for research, the
 * academic workflow).
 *
 * The plugin has grown a great deal (seven layouts, five row sources, write-back, a summary row, full-text +
 * semantic + ask search with image OCR and searchable links, a quick launcher, live Zotero, metadata fill,
 * literature notes, PDF/Office annotation, and import/export/copy/share of whole views). Two failure modes to avoid: being out of date, and dumping all of
 * that on someone's first minute. So the guide steps through it — one idea per screen, every step skippable —
 * and pushes the optional/advanced surface to the end ("a few more things when you want them") rather than the
 * front, so newcomers are pointed at everything without being overwhelmed by it.
 */
export class WelcomeModal extends Modal {
  private index = 0;
  private bodyEl!: HTMLElement;
  private dotsEl!: HTMLElement;
  private backEl!: HTMLButtonElement;
  private nextEl!: HTMLButtonElement;

  constructor(
    app: App,
    private readonly actions: WelcomeActions,
  ) {
    super(app);
  }

  override onOpen(): void {
    const { contentEl, titleEl } = this;
    titleEl.setText("Getting started with Knowledge Views");
    contentEl.addClass("kvs-welcome");

    this.bodyEl = contentEl.createDiv({ cls: "kvs-welcome-body" });

    const foot = contentEl.createDiv({ cls: "kvs-welcome-foot" });
    this.dotsEl = foot.createDiv({ cls: "kvs-welcome-dots" });
    const nav = foot.createDiv({ cls: "kvs-welcome-nav" });
    this.backEl = nav.createEl("button", { text: "Back" });
    this.backEl.addEventListener("click", () => this.go(this.index - 1));
    this.nextEl = nav.createEl("button", { cls: "mod-cta", text: "Next" });
    this.nextEl.addEventListener("click", () => {
      if (this.index === this.steps().length - 1) this.close();
      else this.go(this.index + 1);
    });

    this.go(0);
  }

  override onClose(): void {
    this.contentEl.empty();
  }

  private go(index: number): void {
    const steps = this.steps();
    this.index = Math.max(0, Math.min(steps.length - 1, index));
    const step = steps[this.index]!;

    this.bodyEl.empty();
    this.bodyEl.createDiv({ cls: "kvs-welcome-step", text: `Step ${this.index + 1} of ${steps.length}` });
    this.bodyEl.createEl("h3", { cls: "kvs-welcome-title", text: step.title });
    this.bodyEl.createEl("p", { cls: "kvs-welcome-lead", text: step.lead });
    step.render(this.bodyEl);

    this.dotsEl.empty();
    steps.forEach((_, i) => {
      const dot = this.dotsEl.createSpan({ cls: "kvs-welcome-dot" });
      dot.toggleClass("is-on", i === this.index);
      dot.addEventListener("click", () => this.go(i));
    });

    this.backEl.toggle(this.index > 0);
    this.nextEl.setText(this.index === steps.length - 1 ? "Done" : "Next");
  }

  /** A row of "do this now" cards. */
  private cards(el: HTMLElement, items: readonly { icon: string; title: string; desc: string; cta?: string; primary?: boolean; run?: () => void }[]): void {
    for (const item of items) {
      const card = el.createDiv({ cls: "kvs-welcome-card" });
      setIcon(card.createSpan({ cls: "kvs-welcome-card-icon" }), item.icon);
      const text = card.createDiv({ cls: "kvs-welcome-card-text" });
      text.createDiv({ cls: "kvs-welcome-card-title", text: item.title });
      text.createDiv({ cls: "kvs-welcome-card-desc", text: item.desc });
      if (item.cta && item.run) {
        const button = card.createEl("button", { cls: item.primary ? "mod-cta" : "", text: item.cta });
        button.addEventListener("click", () => {
          this.close();
          item.run?.();
        });
      }
    }
  }

  /** A compact icon + label + one-liner list (for explaining, not for acting). */
  private list(el: HTMLElement, items: readonly { icon: string; name: string; desc: string }[]): void {
    const box = el.createDiv({ cls: "kvs-welcome-list" });
    for (const item of items) {
      const line = box.createDiv({ cls: "kvs-welcome-line" });
      setIcon(line.createSpan({ cls: "kvs-welcome-line-icon" }), item.icon);
      const text = line.createDiv({ cls: "kvs-welcome-line-text" });
      text.createSpan({ cls: "kvs-welcome-line-name", text: item.name });
      text.createSpan({ cls: "kvs-welcome-line-desc", text: item.desc });
    }
  }

  private steps(): Step[] {
    const steps: Step[] = [
      {
        title: "Get your first view on screen",
        lead: "Knowledge Views gathers table rows scattered across your notes into one live view. Edit a cell here and it writes straight back to the note it came from. Pick the quickest way in:",
        render: (el) =>
          this.cards(el, [
            {
              icon: "file-text",
              title: "Use a note I already have",
              desc: "Open a note with a Markdown table, and turn it into a view. Your headers become the columns — no setup.",
              cta: "Create from current note",
              primary: true,
              run: () => this.actions.onUseNote(),
            },
            {
              icon: "layout-template",
              title: "Start from a template",
              desc: "Reading list, task tracker, project log, image gallery, literature review — each creates an example note and a matching view you can edit or delete.",
              cta: "Browse templates",
              run: () => this.actions.onTemplate(),
            },
            {
              icon: "square-dashed",
              title: "Start from scratch",
              desc: "Create an empty view and point it at a folder. It finds the table rows there for you.",
              cta: "New blank view",
              run: () => this.actions.onBlank(),
            },
          ]),
      },
      {
        title: "The same rows, shown seven ways",
        lead: "A view holds your data once. Layouts are just different ways of looking at it — switching layout never changes the data, the filter, or the source notes.",
        render: (el) => {
          this.list(el, [
            { icon: "table-2", name: "Table", desc: "the spreadsheet view — sort, resize, edit in place, total at the foot." },
            { icon: "layout-grid", name: "Cards", desc: "one card per row, good for browsing." },
            { icon: "columns-3", name: "Board", desc: "kanban columns by any field; drag to change it." },
            { icon: "calendar", name: "Calendar", desc: "rows on their date; drag to reschedule." },
            { icon: "images", name: "Gallery", desc: "your images as cards, with live size, shape and fit controls." },
            { icon: "bar-chart-3", name: "Chart", desc: "count or sum your rows into a bar or line chart." },
            { icon: "sigma", name: "Pivot", desc: "cross-tabulate and total." },
          ]);
          el.createEl("p", {
            cls: "kvs-welcome-tip",
            text: "Add as many layouts to one view as you like — the switcher at the top-left of the dashboard moves between them. Three of them (Board, Calendar, Pivot) also work inside Obsidian's own Bases.",
          });
        },
      },
      {
        title: "Tell it what counts as a row",
        lead: "Table rows are the default, but a row can come from other places too. You choose this per view, in its settings under Sources.",
        render: (el) => {
          this.list(el, [
            { icon: "table", name: "Table rows", desc: "each row of every Markdown table in the note." },
            { icon: "file-text", name: "Note properties", desc: "the note's frontmatter becomes one row per note." },
            { icon: "check-square", name: "Tasks", desc: "every checkbox item becomes a row." },
            { icon: "text-cursor-input", name: "Inline fields", desc: "key:: value pairs in the body." },
            { icon: "sheet", name: "Excel rows", desc: "worksheet rows, if you enable Excel in settings." },
          ]);
          el.createEl("p", {
            cls: "kvs-welcome-tip",
            text: "Combine several and each keeps its own rows. If you'd rather a note's properties describe its table rows, switch that view to \"Add note values to each row\" — and if a header exists in two sources, bind the column to the one you mean.",
          });
        },
      },
      {
        title: "Find it — and jump straight to it",
        lead: "Two ways in: a quick launcher to jump to the note you meant, and a full search view to explore. Both read far more than titles — the text of your notes, rows, annotations, attachments (PDF, Word, PowerPoint, Excel, EPUB), the links you've saved, and even text inside images. No companion plugin.",
        render: (el) => {
          this.cards(el, [
            {
              icon: "navigation",
              title: "Jump to a note",
              desc: "Type a few letters; the note you meant is at the top — an exact title or alias match always leads, never the notes that merely mention it. Enter opens it. (Bind a hotkey to “Quick search”.)",
              cta: "Open quick search",
              primary: true,
              run: () => this.actions.onQuickSearch(),
            },
            {
              icon: "text-search",
              title: "Search everything",
              desc: "Results jump to the exact page of a PDF, the exact heading of a note, or the exact row.",
              cta: "Open search",
              run: () => this.actions.onSearch(),
            },
          ]);
          this.list(el, [
            { icon: "search", name: "Keyword", desc: '"exact phrases", -exclude, tag:x, /regex/, and typo tolerance.' },
            { icon: "sparkles", name: "Semantic", desc: "finds notes by meaning, even when the words differ. Fully offline." },
            { icon: "message-square", name: "Ask", desc: "ask a question; get the passages that answer it, with sources." },
            { icon: "scan-text", name: "Image text (OCR)", desc: "make screenshots searchable — turn it on in settings; runs offline." },
          ]);
        },
      },
      {
        title: "Take your data anywhere",
        lead: "Nothing here is locked in. Bring data in, send a view out in whatever format you need, copy rows that paste as real tables, or share a whole view — from the dashboard toolbar (and, for copy, select rows first).",
        render: (el) => {
          this.list(el, [
            { icon: "file-input", name: "Import", desc: "bring tables in from CSV, Markdown or Excel (or references from BibTeX) — each becomes a note and a view." },
            { icon: "file-output", name: "Export", desc: "send a view to Word, Excel, PDF, CSV or Markdown — images embedded and links kept live, with a preview as you go." },
            { icon: "copy", name: "Copy as ▾", desc: "copy rows as a Markdown table, CSV, JSON, bullets — or re-importable KVS rows. Paste into Word, Docs or Excel and you get a real table, not text." },
            { icon: "code", name: "Embed a view", desc: "“Copy as live view” gives you a block to paste into any note — the view renders there, live." },
            { icon: "package", name: "Archive a view", desc: "save a self-contained .kvspack — settings, data and every image/attachment bundled in, optionally encrypted. It opens read-only anywhere (no importing needed) and restores even if the original notes are gone. A lighter .kvsview saves just the settings." },
          ]);
        },
      },
    ];

    if (this.actions.academicKit) {
      steps.push({
        title: "Your research workflow",
        lead: "The Academic Research kit is on. Turn it on for a view (its settings → Research) and these become available — all optional, use only what you need:",
        render: (el) =>
          this.list(el, [
            { icon: "library", name: "Your Zotero library", desc: "browse it live (no export), and send papers straight to a dashboard." },
            { icon: "download", name: "Fill in the details", desc: "right-click a row → fill metadata from a DOI or from Zotero, with exact Better BibTeX cite keys." },
            { icon: "sticky-note", name: "One note per paper", desc: "promote a row to a dedicated note, matched by DOI so it's never duplicated." },
            { icon: "highlighter", name: "Annotate", desc: "highlight PDFs (they sync into the note as callouts); collect Word/Excel/PowerPoint comments too." },
            { icon: "graduation-cap", name: "Academic columns & rollups", desc: "citation key, authors, DOI, arXiv — with lookups — and totals across every paper note." },
          ]),
      });
    }

    steps.push({
      title: "You're set — a few more things when you want them",
      lead: "That's everything you need to start. These are here whenever you reach for them:",
      render: (el) => {
        this.list(el, [
          { icon: "sigma", name: "Summary row", desc: "totals, averages and counts at the foot of a table — toggle it per view." },
          { icon: "settings-2", name: "View settings", desc: "each view's own sources, columns, filters and layouts — from the dashboard toolbar." },
          { icon: "settings", name: "Plugin settings", desc: "grouped into sections; the search box there finds any setting." },
          { icon: "life-buoy", name: "This guide", desc: 'reopen it any time with the "Getting started" command.' },
        ]);
      },
    });

    return steps;
  }
}
