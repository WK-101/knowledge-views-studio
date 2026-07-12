/**
 * Starter templates: each creates a demo note with a filled Markdown table AND a matching view, so a
 * new user learns by modifying a working example. Content is a function so date columns can be filled
 * relative to today (a calendar of past dates isn't useful).
 */
export interface StarterTemplate {
  readonly id: string;
  readonly label: string;
  readonly description: string;
  readonly icon: string;
  /** Folder + note names for the demo note (created under "KVS Examples/"). */
  readonly folderName: string;
  readonly noteName: string;
  readonly viewName: string;
  readonly viewType: string;
  readonly viewOptions: Readonly<Record<string, string>>;
  /** Optional extra layouts (tabs) over the same data — showcases the multi-layout model. */
  readonly layouts?: readonly { readonly name: string; readonly type: string; readonly options?: Readonly<Record<string, string>> }[];
  /** Optional explicit columns (name + type). Lets a template pre-type academic/other columns. */
  readonly columns?: readonly { readonly name: string; readonly type: string }[];
  /** Turn the Academic Research kit on for the created view. */
  readonly academicKit?: boolean;
  /** Group the view's rows by this field (e.g. a synthesis matrix grouped by theme). */
  readonly group?: { readonly field: string };
  /** Only offer this template when the given kit is enabled in settings. */
  readonly requiresKit?: "academic";
  readonly content: () => string;
}

function iso(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

export const STARTER_TEMPLATES: readonly StarterTemplate[] = [
  {
    id: "image-gallery",
    label: "Image gallery",
    description: "Every picture across your rows as its own card. Shows off the Gallery layout.",
    icon: "images",
    folderName: "Image gallery",
    noteName: "Image gallery",
    viewName: "Image gallery",
    viewType: "gallery",
    viewOptions: {},
    columns: [
      { name: "Title", type: "text" },
      { name: "Images", type: "image" },
      { name: "Notes", type: "text" },
    ],
    content: () =>
      [
        "# Image gallery",
        "",
        "A row can hold **several** images, and images can live in more than one column — the Gallery",
        "layout finds every one of them and gives each its own card.",
        "",
        "Replace the placeholders below with real embeds from your vault, e.g. `![[photo.png]]`.",
        "External links work too: `![](https://example.com/photo.jpg)`.",
        "",
        "| Title | Images | Notes |",
        "| --- | --- | --- |",
        "| Trip to the coast | ![[coast-1.png]] ![[coast-2.png]] | Two images in one cell |",
        "| Sketches | ![[sketch.png]] | Add your own |",
        "| Reference shots |  | Drop images in and they appear here |",
        "",
        "Use the slider in the gallery toolbar to resize the cards, the ratio slider to reshape them,",
        "and Fill / Fit to choose between cropping and showing the whole image.",
        "",
      ].join("\n"),
  },
  {
    id: "reading-list",
    label: "Reading list",
    description: "Track books as a board. Drag cards between To read / Reading / Done.",
    icon: "book-open",
    folderName: "Reading list",
    noteName: "Reading list",
    viewName: "Reading list",
    viewType: "kanban",
    viewOptions: { groupField: "Status" },
    content: () =>
      [
        "# Reading list",
        "",
        "A few books to get you started. Move cards between columns, or edit any cell — changes save straight back into this note's table.",
        "",
        "| Title | Author | Status | Rating |",
        "| --- | --- | --- | --- |",
        "| The Pragmatic Programmer | Hunt & Thomas | Reading | 4 |",
        "| Thinking, Fast and Slow | Daniel Kahneman | To read |  |",
        "| Deep Work | Cal Newport | Done | 5 |",
        "| The Design of Everyday Things | Don Norman | To read |  |",
        "",
      ].join("\n"),
  },
  {
    id: "task-tracker",
    label: "Task tracker",
    description: "See tasks on a calendar by their due date. Edit dates to reschedule.",
    icon: "calendar-check",
    folderName: "Task tracker",
    noteName: "Tasks",
    viewName: "Task tracker",
    viewType: "calendar",
    viewOptions: { dateField: "Due" },
    content: () =>
      [
        "# Task tracker",
        "",
        "Tasks laid out on a calendar by due date. Edit any cell to update the source note.",
        "",
        "| Task | Status | Priority | Due |",
        "| --- | --- | --- | --- |",
        `| Draft project brief | Doing | High | ${iso(2)} |`,
        `| Review designs | Todo | Medium | ${iso(6)} |`,
        `| Ship release | Todo | High | ${iso(13)} |`,
        `| Retrospective | Todo | Low | ${iso(20)} |`,
        "",
      ].join("\n"),
  },
  {
    id: "project-log",
    label: "Project log",
    description: "A running log of updates as a sortable table.",
    icon: "clipboard-list",
    folderName: "Project log",
    noteName: "Project log",
    viewName: "Project log",
    viewType: "table",
    viewOptions: {},
    content: () =>
      [
        "# Project log",
        "",
        "| Date | Update | Owner | Status |",
        "| --- | --- | --- | --- |",
        `| ${iso(-7)} | Project kickoff | Alex | Done |`,
        `| ${iso(-4)} | First prototype ready | Sam | Done |`,
        `| ${iso(-1)} | User feedback round | Priya | In progress |`,
        `| ${iso(0)} | Plan next milestone | Alex | Todo |`,
        "",
      ].join("\n"),
  },
  {
    id: "crm",
    label: "Simple CRM",
    description: "Contacts as cards, grouped-friendly by stage.",
    icon: "contact",
    folderName: "Contacts",
    noteName: "Contacts",
    viewName: "Contacts",
    viewType: "cards",
    viewOptions: {},
    content: () =>
      [
        "# Contacts",
        "",
        "A lightweight CRM. Each row is a contact; edit any field to update this note.",
        "",
        "| Name | Company | Stage | Last contact | Notes |",
        "| --- | --- | --- | --- | --- |",
        `| Jordan Lee | Acme Inc | Lead | ${iso(-14)} | Intro call scheduled |`,
        `| Riya Patel | Globex | Qualified | ${iso(-7)} | Sent proposal |`,
        `| Chris Owen | Initech | Customer | ${iso(-3)} | Renewal in Q3 |`,
        `| Dana Kim | Umbrella | Lead | ${iso(-1)} | Met at conference |`,
        "",
      ].join("\n"),
  },
  {
    id: "project-tracker",
    label: "Project tracker (multiple layouts)",
    description: "One set of tasks shown three ways — a table, a board by status, and a calendar by due date.",
    icon: "layout-dashboard",
    folderName: "Project tracker",
    noteName: "Tasks",
    viewName: "Project tracker",
    viewType: "table",
    viewOptions: {},
    layouts: [
      { name: "Table", type: "table" },
      { name: "Board", type: "kanban", options: { groupField: "Status" } },
      { name: "Calendar", type: "calendar", options: { dateField: "Due" } },
    ],
    content: () =>
      [
        "# Tasks",
        "",
        "One data source, several layouts. Switch layouts from the tabs on the view — the rows are the",
        "same everywhere, so a change in one layout shows in all of them.",
        "",
        "| Task | Status | Priority | Due |",
        "| --- | --- | --- | --- |",
        `| Draft project brief | Done | High | ${iso(-3)} |`,
        `| Set up repository | Doing | High | ${iso(1)} |`,
        `| Design data model | Doing | Medium | ${iso(2)} |`,
        `| Write onboarding docs | Todo | Medium | ${iso(5)} |`,
        `| Plan launch | Todo | Low | ${iso(9)} |`,
        `| Collect feedback | Todo | Low | ${iso(14)} |`,
        "",
      ].join("\n"),
  },

  {
    id: "literature-review",
    label: "Literature review",
    description: "A citation table across your paper notes: authors, year, DOI, status, findings. Kit.",
    icon: "graduation-cap",
    folderName: "Literature Review",
    noteName: "Literature Review",
    viewName: "Literature Review",
    viewType: "table",
    viewOptions: {},
    academicKit: true,
    requiresKit: "academic",
    columns: [
      { name: "Cite key", type: "citekey" },
      { name: "Authors", type: "authors" },
      { name: "Year", type: "number" },
      { name: "Title", type: "text" },
      { name: "Venue", type: "text" },
      { name: "Status", type: "select" },
      { name: "Rating", type: "rating" },
      { name: "DOI", type: "doi" },
      { name: "Key findings", type: "markdown" },
    ],
    layouts: [{ name: "Reading board", type: "kanban", options: { groupField: "Status" } }],
    content: () =>
      [
        "# Literature Review",
        "",
        "One row per paper. Add a table like this to each of your reading notes and KVS aggregates them all.",
        "",
        "| Cite key | Authors | Year | Title | Venue | Status | Rating | DOI | Key findings |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
        "| pennington2014 | Pennington; Socher; Manning | 2014 | GloVe: Global Vectors for Word Representation | EMNLP | Read | 5 | 10.3115/v1/D14-1162 | Count-based embeddings rival prediction-based ones |",
        "| vaswani2017 | Vaswani; Shazeer; Parmar; Uszkoreit | 2017 | Attention Is All You Need | NeurIPS | Read | 5 | 10.48550/arXiv.1706.03762 | Self-attention replaces recurrence; the Transformer |",
        "| devlin2019 | Devlin; Chang; Lee; Toutanova | 2019 | BERT: Pre-training of Deep Bidirectional Transformers | NAACL | Reading | 4 | 10.18653/v1/N19-1423 | Masked-LM pretraining for bidirectional context |",
        "| brown2020 | Brown; Mann; Ryder | 2020 | Language Models are Few-Shot Learners | NeurIPS | To read | | 10.48550/arXiv.2005.14165 | Scaling yields in-context few-shot ability |",
        "",
      ].join("\n"),
  },

  {
    id: "synthesis-matrix",
    label: "Synthesis matrix",
    description: "Aggregate findings from every paper note, grouped by theme — the lit-review matrix. Kit.",
    icon: "layout-grid",
    folderName: "Synthesis Matrix",
    noteName: "Findings — starter",
    viewName: "Synthesis Matrix",
    viewType: "table",
    viewOptions: {},
    academicKit: true,
    requiresKit: "academic",
    group: { field: "Theme" },
    columns: [
      { name: "Paper", type: "citekey" },
      { name: "Theme", type: "select" },
      { name: "Finding", type: "markdown" },
      { name: "Evidence", type: "text" },
    ],
    content: () =>
      [
        "# Findings — starter",
        "",
        "> How to use: give each paper note a `## Findings` table with the columns below. KVS gathers the",
        "> rows from **all** your notes into one matrix, grouped by Theme — so you can read every paper's",
        "> take on a theme side by side. Add rows here, or in any other note in this folder.",
        "",
        "## Findings",
        "",
        "| Paper | Theme | Finding | Evidence |",
        "| --- | --- | --- | --- |",
        "| vaswani2017 | Architecture | Self-attention replaces recurrence entirely | Fig. 1; §3 |",
        "| vaswani2017 | Efficiency | Parallelizable; trains faster than RNNs | Table 2 |",
        "| devlin2019 | Architecture | Bidirectional context via masked-LM | §3.1 |",
        "| devlin2019 | Transfer | Fine-tuning beats feature extraction | Table 7 |",
        "| brown2020 | Transfer | In-context learning with no gradient updates | §3 |",
        "| brown2020 | Efficiency | Scaling laws hold to 175B params | Fig. 3.1 |",
        "",
      ].join("\n"),
  },

  {
    id: "paper-library",
    label: "Paper library",
    description: "One row per paper for your whole library — metadata + summary, themes, critique. Kit.",
    icon: "library",
    folderName: "Paper Library",
    noteName: "Library",
    viewName: "Paper Library",
    viewType: "table",
    viewOptions: {},
    academicKit: true,
    requiresKit: "academic",
    columns: [
      { name: "Cite key", type: "citekey" },
      { name: "Authors", type: "authors" },
      { name: "Year", type: "number" },
      { name: "Title", type: "text" },
      { name: "Venue", type: "text" },
      { name: "Status", type: "select" },
      { name: "Read", type: "date" },
      { name: "Rating", type: "rating" },
      { name: "Tags", type: "tags" },
      { name: "Summary", type: "markdown" },
      { name: "My notes", type: "markdown" },
      { name: "DOI", type: "doi" },
      { name: "Cites", type: "relation" },
      { name: "Cites checked", type: "date" },
      { name: "Note", type: "link" },
    ],
    layouts: [{ name: "Reading board", type: "kanban", options: { groupField: "Status" } }],
    content: () =>
      [
        "# Library",
        "",
        "> One row per paper. Fill a DOI and right-click → **Fill details from DOI** to auto-complete",
        "> authors/title/year/venue — or **Add papers by DOI** to capture several at once. Right-click a",
        "> row → **View details** to open the paper card: write a few hundred words in **Summary** and",
        "> **My notes**, and **paste figures** straight in (they save to your vault). **Tags** use #hashtags,",
        "> so they appear in Obsidian's tag pane, search and graph, and follow a paper when you promote it.",
        "> Promote a paper to a",
        "> dedicated note when it earns one; split into several notes by year or topic once you reach",
        "> thousands. KVS shows them all as one library.",
        "",
        "| Cite key | Authors | Year | Title | Venue | Status | Read | Rating | Tags | Summary | My notes | DOI | Cites | Cites checked | Note |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
        "| vaswani2017 | Vaswani; Shazeer | 2017 | Attention Is All You Need | NeurIPS | Read | 2024-01-15 | 5 | #attention #architecture | Introduces the Transformer; self-attention replaces recurrence entirely. | Positional encoding feels ad hoc; revisit for my method. | 10.5555/3295222 |  |  |  |",
        "| devlin2019 | Devlin; Chang; Lee | 2019 | BERT | NAACL | Read | 2024-02-03 | 4 | #pretraining #transfer | Masked-LM pretraining for bidirectional context. | Strong baseline; compute-heavy to reproduce. | 10.18653/v1/N19-1423 |  |  |  |",
        "| brown2020 | Brown; Mann; Ryder | 2020 | GPT-3: Few-Shot Learners | NeurIPS | Reading |  | 4 | #scaling #transfer | Scaling yields in-context few-shot ability. | Closed model; note the scaling-law figures. | 10.48550/arXiv.2005.14165 |  |  |  |",
        "",
      ].join("\n"),
  },
];

/** Templates available given the enabled kits (hides kit-only templates when the kit is off). */
export function availableTemplates(enableAcademicKit: boolean): readonly StarterTemplate[] {
  return STARTER_TEMPLATES.filter((t) => t.requiresKit !== "academic" || enableAcademicKit);
}
