# Team Task Board

Every block below aggregates the task rows from the three notes in `Projects/`.
Edit a cell or drag a card and the change is written **back into the source table**
in the originating note (with "Inline editing" enabled in settings).

## Board — grouped by status

Drag a card between columns to change that task's status in its source note.

```knowledge-view
view: kanban
folders: Projects
columns: Task, Status, Owner, Points
option.groupField: Status
```

## All tasks — sorted by due date

Double-click a cell to edit it. Tick the checkboxes to select rows, then use the
bulk bar to set a field across every selected task at once.

```knowledge-view
view: table
folders: Projects
columns: Task, Status, Owner, Due:date, Points:number
sort: Due asc
```

## Summary — story points by owner and status

```knowledge-view
view: pivot
folders: Projects
option.rowField: Owner
option.columnField: Status
option.aggregate: sum
option.aggregateField: Points
```

## Calendar — tasks by due date

Use the ‹ › buttons to move to July 2026, where most of these tasks are due.

```knowledge-view
view: calendar
folders: Projects
option.dateField: Due
```
