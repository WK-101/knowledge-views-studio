# Knowledge Views Studio — example vault

This folder is a tiny, self-contained demo. To try it:

1. Build the plugin (`npm install && npm run build`) or install it into a vault.
2. Copy this `examples/` folder into an Obsidian vault that has the plugin enabled
   (or open this folder as a vault for a quick look).
3. Open **Dashboards/Task Board.md** and switch to Reading view (or Live Preview).

## What you're looking at

`Projects/` holds three ordinary notes. Each contains a Markdown **table** of tasks
with the same columns: `Task`, `Status`, `Owner`, `Points`, `Due`. Nothing is stored
in frontmatter — the rows live in the body of each note.

**Dashboards/Task Board.md** contains four ` ```knowledge-view ` blocks that read
those rows from across all three notes and present them as:

- a **Board** grouped by `Status` (drag a card to write the new status back),
- a **Table** sorted by due date (double-click to edit; multi-select for bulk edits),
- a **Summary** pivot of story points by owner × status, and
- a **Calendar** placing each task on its due date.

## The block format

Blocks use a small `key: value` format. View-specific settings use an `option.` prefix:

```
view: kanban          # table | cards | kanban | calendar | pivot
folders: Projects     # scope to one or more folders
columns: Task, Status, Owner, Points
sort: Due asc
option.groupField: Status   # a setting specific to the chosen view
```

For richer setups (typed/select columns with a fixed board-column order, computed
fields, saved filters) create a **profile** in *Settings → Knowledge Views Studio*
and reference it with `profile: <name>`.
