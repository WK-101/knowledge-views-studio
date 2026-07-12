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
 * screen, then look at it differently, then tell it what a row is, then find things.
 *
 * The plugin has grown a lot (layouts, sources, annotations, full-text and semantic search), and the old
 * single-screen welcome had two failure modes: it was out of date, and listing everything at once is how
 * you overwhelm someone on their first minute. Stepping through it keeps each screen to one idea, and
 * every step can be skipped.
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
        title: "The same rows, shown six ways",
        lead: "A view holds your data once. Layouts are just different ways of looking at it — switching layout never changes the data, the filter, or the source notes.",
        render: (el) => {
          this.list(el, [
            { icon: "table-2", name: "Table", desc: "the spreadsheet view — sort, resize, edit in place." },
            { icon: "layout-grid", name: "Cards", desc: "one card per row, good for browsing." },
            { icon: "columns-3", name: "Board", desc: "kanban columns by any field; drag to change it." },
            { icon: "calendar", name: "Calendar", desc: "rows on their date; drag to reschedule." },
            { icon: "images", name: "Gallery", desc: "every image in your rows as its own card." },
            { icon: "sigma", name: "Pivot", desc: "cross-tabulate and total." },
          ]);
          el.createEl("p", {
            cls: "kvs-welcome-tip",
            text: "Add as many layouts to one view as you like — the switcher at the top-left of the dashboard moves between them.",
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
        title: "Find anything you've written or read",
        lead: "The search view goes well beyond note titles: it reads the full text of your notes, your table rows, your annotations, and your attachments — PDFs, Word, PowerPoint, Excel and EPUB — with no companion plugin.",
        render: (el) => {
          this.list(el, [
            { icon: "search", name: "Keyword", desc: '"exact phrases", -exclude, tag:x, /regex/, and typo tolerance.' },
            { icon: "sparkles", name: "Semantic", desc: "finds notes by meaning, even when the words differ. Fully offline." },
            { icon: "message-square", name: "Ask", desc: "ask a question; get the passages that answer it, with sources." },
          ]);
          this.cards(el, [
            {
              icon: "text-search",
              title: "Try it now",
              desc: "Results jump to the exact page of a PDF, the exact heading of a note, or the exact row.",
              cta: "Open search",
              primary: true,
              run: () => this.actions.onSearch(),
            },
          ]);
        },
      },
    ];

    if (this.actions.academicKit) {
      steps.push({
        title: "For research work",
        lead: "The Academic Research kit is on, so a few extras are available once you enable it on a view (its settings → Research).",
        render: (el) =>
          this.list(el, [
            { icon: "highlighter", name: "Annotate PDFs", desc: "highlight in Obsidian; highlights sync into the note as callouts." },
            { icon: "graduation-cap", name: "Academic columns", desc: "citation key, authors, DOI, arXiv — with one-click lookups." },
            { icon: "sigma", name: "Rollups", desc: "aggregate findings across every paper note." },
          ]),
      });
    }

    steps.push({
      title: "You're set",
      lead: "That's everything you need. A few things worth knowing, whenever you want them:",
      render: (el) => {
        this.list(el, [
          { icon: "settings-2", name: "View settings", desc: "each view's own sources, columns, filters and layouts — from the dashboard toolbar." },
          { icon: "settings", name: "Plugin settings", desc: "grouped into sections; the search box there finds any setting." },
          { icon: "life-buoy", name: "This guide", desc: 'reopen it any time with the "Getting started" command.' },
        ]);
      },
    });

    return steps;
  }
}
