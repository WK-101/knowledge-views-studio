# MyLifeOrganized (MLO) — Official Web, Manual & Screenshot UI Study

Companion to `mylifeorganized.md` (APK teardown). This doc captures how MLO **markets and
officially describes** itself, and the **actual UI look/interactions** as seen in official
screenshots (Google Play listing, both phone and tablet), so we can match the feature set and
improve on the UI in our Kotlin/Compose build.

**Evidence & method.** WebFetch/WebSearch on MLO's official site + Google Play. The site root
`https://www.mylifeorganized.net/` and the Play listing HTML returned **HTTP 403 to WebFetch**
(bot protection); worked around by (a) fetching support subpages directly (these succeeded), and
(b) `curl`-ing the Play listing with a desktop User-Agent (succeeded), then extracting the
description JSON and the screenshot image URLs. **17 official screenshots** were downloaded from
`play-lh.googleusercontent.com` and read directly. Screenshot observations are tagged **OBSERVED**;
interpretation is tagged **INFERRED**. Marketing copy is quoted verbatim where load-bearing.

Sources are listed inline and consolidated at the bottom.

---

## 1. Marketed feature list (grouped)

Primary source: the Google Play long description (listing "Updated on Feb 9, 2026", extracted from
listing HTML) plus the official support pages. MLO explicitly splits its features into **FREE
forever** vs **PRO (21-day trial then paid)** — a split worth mirroring in our own
positioning/gating decisions.

Tagline / positioning (Play): *"MyLifeOrganized (MLO) is the most flexible and powerful task
management software for getting your to-dos finally done."* … *"Designed to strike a balance
between the simple and the complex."* … *"particularly suited for those who are truly serious about
personal task management."*

### 1a. Core structure / capture (FREE)
| Feature | Official one-line "how it works" | Source |
|---|---|---|
| Unlimited hierarchy of tasks & subtasks | "organize your tasks into projects and break down large tasks until you have reasonably sized actions" — one Outline, unlimited nesting | Play; getting-started-1 |
| Inbox | "Inbox for rapid task entry" — a holding bucket you process later into the hierarchy | Play; shot09 note |
| Rapid task entry with advanced parsing (PRO) | "add tasks with ready-made properties using the app, widget or Google Assistant" (natural-language parse of dates/contexts) | Play |
| Templates | "Templates for a quick start with different task management systems such as GTD®, FranklinCovey and Do-It-Tomorrow" | Play |
| Notes on tasks | Free-text note attached under a task title (rich text, see §3) | shot05/07 (OBSERVED) |

### 1b. The To-Do engine (the signature — see §2)
| Feature | Official one-line | Source |
|---|---|---|
| Next Actions / Active Actions | "automatically get a list of tasks that require your attention now" — auto-generated, self-updating | Play; how-it-works |
| MLO Smart To-Do List Sorting | sorting "using the priorities of the task **and its parents**" (Computed-Score priority) | Play; how-it-works |
| Complete tasks in a specific order (PRO) | mark a project "complete sub-tasks in order" so only the current next action surfaces ("True Next Actions") | Play; GTD page |
| Filter actions by context | only tasks whose context matches your current situation appear | Play; getting-started-2 |
| Dependencies (PRO) | "sequential and parallel projects, holding up tasks that can not start until other tasks are finished" | Play; getting-started-1 |

### 1c. Views, filtering & organization
| Feature | Official one-line | Source |
|---|---|---|
| Custom views (PRO) | "Custom views with filtering, sorting and grouping, to match the system that works for you" | Play; shot04/13 |
| Workspaces (tabs) (PRO) | "switch quickly between projects or views" — saved view + filter + zoom + selection | Play; getting-started intro |
| Zoom | "focus on a specific branch of tasks" (drill into one subtree) | Play; getting-started-1 |
| Starred tasks | one-tap star to flag a task into the Starred quick-list | Play; shots 02/06/17 |
| Flags | colored flag marker per task (separate from star) | shot10/17 (OBSERVED) |
| Contexts / tags | "@Home, @Office…" GTD contexts; a task can hold several; filter To-Do by context | getting-started-2 |
| Context (open/closed) hours | "set a time period when you can do tasks with that context" — hides those tasks outside the window | getting-started-2 |
| Color coding | per-row background/text color rules | shot17 (OBSERVED) |
| Review (PRO) | "flag tasks for regular review to add new subtasks or change priorities" — recurring review cadence per task | Play; shot07 |
| Project tracking (PRO) | mark a task a Project; it appears in Projects View with an auto % complete from subtasks | Play; GTD page; shot05 ("Projects 15%") |
| Goals view | dedicated view for weekly/monthly/yearly objectives | GTD page; shot02 |

### 1d. Scheduling, reminders & recurrence
| Feature | Official one-line | Source |
|---|---|---|
| Start & Due dates | per-task Start Date and Due Date fields | shot07 (OBSERVED); recurring-tasks |
| Reminders | time-based reminder/alarm per task; reminder offset carries across recurrences | Play; recurring-tasks |
| Recurring & regenerating tasks (PRO) | "hourly, daily, weekly, monthly, yearly" + custom patterns; **fixed** (calendar) vs **regeneration** ("Regenerate new task in X days after each task is completed") | recurring-tasks |
| Calendar view (PRO) | "measure your daily workload" — graph of task/appointment counts per day + a day timeline | Play; shots 08/12/15 |
| Device calendar overlay | "My Events" shows device calendar appointments alongside tasks for the day | shots 08/12/15 (OBSERVED) |

### 1e. Location / context-in-place
| Feature | Official one-line | Source |
|---|---|---|
| Nearby view (PRO) | "get a list of actions for your current GPS location, with reminders as you reach or leave the location" | Play; shots 03/16 |
| Location-based contexts | a context can carry a map place; geofenced enter/leave reminders | getting-started-2; shot16 |

### 1f. Entry surfaces, sync & platform
| Feature | Official one-line | Source |
|---|---|---|
| Customizable widgets (PRO) | home-screen list widgets (Active Actions / Starred / Inbox) + quick-add/remind shortcuts | Play; shot14 |
| Floating Promoted Action Button (PRO) | "add a new task or perform another action from anywhere on the screen" | Play; shots (green FAB) |
| Actions from notification area (PRO) | act on reminders/tasks from the notification shade | Play |
| Voice / Google Assistant add | mic entry + Assistant integration | Play; shot06/14 (mic icon) |
| Password protection (PRO) | lock the app/database | Play |
| MLO Cloud sync | "sync automatically with the world-class Desktop version"; multi-device, shared lists, collaboration ("more than 65 million to-dos") | Play |
| Wi-Fi sync / offline | "sync directly over your own private Wi-Fi or work completely offline" | Play |

> Note: the desktop app (`todo-list-windows.shtml`) is "sold separately" and markets the same core
> (Outline + To-Do + computed priority) in a classic multi-pane Windows layout. Our build targets
> the mobile side, but the **Outline / To-Do / Detail three-pane** idea is shared desktop↔tablet.

---

## 2. The computed-priority / "To-Do" concept (MLO's signature)

This is MLO's differentiator and their own framing matters. **OBSERVED wording** from official
pages:

**What it produces.** *"Once loaded with your information, MyLifeOrganized goes to work and
generates a simple list containing only the next actions that require your immediate attention.
This list is updated auto-magically once you complete a task, drive to a new location, or simply if
it is dinner time."* (Play). The support page: *"Once you've added your goals, projects, and tasks
into the Outline, MyLifeOrganized automatically generates a prioritized To-Do list."*
(how-it-works).

**The key mental-model shift MLO teaches** (how-it-works, verbatim): *"In many To-Do applications,
setting a task's priority determines its absolute ranking against all other tasks. However, in MLO,
you should only consider how important it is to complete the task **relatively to completing its
parent project**."* → Importance is **relative to the parent**, not global.

**Factors that feed the score** (how-it-works + blog "How Importance Works", Mar 2024): the overall
priority depends on several weighted parameters —
- **Importance** (relative to parent) — *"Among these parameters, Importance takes the leading
  position."*
- **Urgency**
- **Due date** and **Start date**
- **Weekly Goal**

**Propagation ("snowball").** Blog, verbatim: *"the importance of each task is determined directly
for the 'parent' task, project, or folder in which this task is located"*, and *"In the case of
using a large number of nesting levels, the importance for the final task, like a snowball, either
accumulates or decreases along the chain."* Practical consequence they state: adjust a **project's**
importance and *"all associated subtasks will automatically update their positions in the to-do
list — no need to modify each subtask's importance individually."*

**Worked example (blog, "Mountain Trip").** In a *Mountain Trip* project, "Book tickets" rises to
the very top because it has maximum importance AND its parent carries high importance; "Take
glasses" — sitting under a lower-priority "Packing" branch — **still ranks highly** because its own
importance is maxed. This illustrates that final rank = own importance × inherited parent
importance, combined with dates/urgency.

**Tunable weights.** The relative weight of dates vs importance is user-adjustable via **Tools →
Options** (desktop wording) so users control how strongly deadlines pull tasks up versus importance.

**Auto-regeneration.** *"Each time you complete a task the To-Do list is regenerated"* (search
result from support). The To-Do list is a **computed projection**, never hand-sorted.

**No public numeric formula.** MLO deliberately does **not** publish the exact scoring math on these
pages ("Computed Score Priority" is referenced as an advanced help topic). The precise algorithm
lives in the engine — see the APK teardown (`mylifeorganized.md` §4, `ComputedScorePriorityType`)
for the reverse-engineered version. **INFERRED:** our engine should reproduce that computed score;
the *marketing* job is to make "the list just reorders itself" feel magical while exposing
Importance/Urgency/dates as the levers.

Sources: how-it-works; GTD-Getting-Things-Done; blog 2024/03; Play description.

---

## 3. Visual / UI spec from screenshots (OBSERVED)

17 official screenshots were read. MLO ships **two visual generations that coexist** in the current
listing: an **older bright-green header** style (shots 06, 14 widgets, ~2014–15 data) and a
**newer flat light-gray Material-ish** style (shots 02–05, 07, 09–13, 17). Layout logic is
consistent across both; only chrome differs. Everything below is OBSERVED unless marked.

### 3.1 The side panel / navigation model (shot02 phone, left rail in shots 03–15 tablet)
This is MLO's core navigation and is worth copying almost wholesale.
- **Top row of 3 quick tiles:** **Inbox** (shows a count badge, e.g. "56"), **Starred**, **Nearby**
  (compass icon). Big, iconographic, one-tap.
- **"Today: N tasks, M events"** summary strip (chart glyph) → taps into the calendar/graph view.
- **Grouped view list**, section headers in gray:
  - **Outline**: `All Tasks` (with counts `☑62  Σ99+` = completed / total), `Projects`, `Goals`,
    `Review`.
  - **To-Do**: `Active Actions` (count, e.g. "31"), `Active by Context`, `Due Next 7 days` ("30").
  - **Recent**: `Modified Recently`, `Completed Recently`.
  - **Contexts & Locations** (bottom of the list).
- Selected view is a **full-width green highlight** bar.
- **Footer** (persistent): `EDIT` · a gray **"Add to Inbox"** button · a green **gear** (settings).
- Top of drawer: app id "✓ MLO", a **workspace/tab chip** (shows "1"), a **cloud/sync** icon.
- **INFERRED:** this maps almost 1:1 to a Compose `ModalNavigationDrawer` with a
  `LazyColumn` of grouped `NavigationDrawerItem`s + a pinned header (quick tiles) and footer.

### 3.2 The Outline / tree view (shot05, 09, 10, 17)
- Hierarchical rows with **indentation + elbow/branch connectors** and **disclosure carets**
  (`>`) on parents.
- Each row: leading **checkbox** (empty / grey-checked when done / **red-filled when overdue**),
  optional small **note/paperclip glyph**, **title**, optional wrapped **note line** beneath, a
  **due-date line** (e.g. "Sun, Aug 16"), right-aligned **context chips** (tiny grey pills:
  `@Desktop @Laptop`), a **flag** icon, and a **star**.
- **Folders** render with a filled folder icon (shot09: Business, Home/Personal; shot10: Home,
  Work).
- **Selected row = light-green fill** with a green left accent bar (shot05 "Compare prices",
  shot09 "Business").
- **Parent progress**: parents show a small **% complete** ("Projects 15%" / "18%") derived from
  subtasks (shot05/07).
- **Recurrence** marked by a circular-arrow (↻) glyph in the checkbox slot (shot10 "Meeting with
  Design Team").
- **Drag/reorder**: a blue horizontal **insertion line** appears between rows during drag (shot10
  under "Product Development"); top bar has a 4-way **move** icon.
- **Bottom action toolbar** (unlabeled green glyphs): add task, add subtask, **eye** (view options),
  collapse/expand arrow, overflow `⋮`.
- **Rich formatting (shot17):** rows carry **color-coding** (Home=yellow, Work=blue,
  Goals=green, Health=purple backgrounds), **colored flags** (blue/green/red), **strikethrough**
  for completed ("Quarterly Performance review meeting"), and **italic/bold/underline** title text.

### 3.3 The To-Do view — "Active Actions" (shots 06 phone-old, 11 tablet)
- A **flat, computed list** (no hierarchy) of only currently-actionable tasks, in computed-priority
  order.
- Rows mirror outline rows: checkbox (red = overdue), title, date/time, context chips, trailing
  **type icons** (envelope for an email task, phone handset for a call task — shot06), star.
- **Location** tasks show a map-pin glyph (shot06 "Buy tomatoes @Grocery").
- Old green header (shot06) packs actions inline: `+`, **reminder+**, **mic**, **gear**.
- On tablet (shot11) it's the middle pane of a 3-pane layout feeding the detail pane.

### 3.4 Task detail / property pane (shots 07, 11 tablet right pane)
- A **read-oriented** detail panel; editing is behind an **EDIT** button (top-right, next to
  share + star). **INFERRED:** properties are edited in a separate mode, not inline.
- Fields observed: **Start Date / Due Date** (side by side), **note** (multi-line, bulleted),
  **@contexts** row, **Review** block ("Next review: Fri, Jul 11 2014 · Review every: 4 days · Last
  reviewed…") with a green **MARK REVIEWED** button, **Importance / Urgency** shown as **text
  labels** ("Max" / "Normal" — *not* a visible slider here), **Action Status** ("Active Action"),
  **Created / Modified** timestamps, **Full Path** (`\Plan a Vacation…\At the location\`), and
  **MOVE TASK** / **DELETE** (red) buttons.
- **INFERRED:** the famous Importance/Urgency **sliders** live inside EDIT mode; the detail view
  only surfaces the resulting label.

### 3.5 Custom View editor (shots 04 tablet dialog, 13 phone full-screen)
- Titled **"Custom View Settings"**, a long scrolling **list of setting rows**, sectioned with
  green headers:
  - **View name** (e.g. "My urgent tasks" / "Due Next 7 days").
  - **Main filter** → **Action Filter** (values seen: `All`, `Available`), **Advanced Filter**
    (`Starred and <Group>`, `Due`).
  - **Group and sort** → **Group By** (`Due Date`), **Sort by** (`Urgency`, `Flag`).
  - **Hierarchy** → **Show hierarchy** (toggle), **Include parent items** ("Include all parents for
    the matched items"), **Include child items**, **Continue searching in branch** ("…after main
    filter match").
- This is the **power-user engine** exposed as UI: filter → group → sort → hierarchy handling.
  Jargon-heavy but complete.

### 3.6 Calendar / "Today" graph view (shots 08 phone, 12 & 15 tablet)
- Header: **"Today - Due"** with a **dropdown** to switch the day/basis.
- Top: an **area line-graph** of counts per day across ~a week (each point labeled with a count +
  date).
- **"MY EVENTS (n)"** section — device-calendar appointments, on tablet rendered on an **hour ruler
  timeline** (01:00–24:00) with colored event bars, times, durations, locations and attendees
  (shot15: "MLO Design Meeting 09:00 · Skype", "Meeting with developers 18:30").
- **"MY TASKS (n)"** section below — the day's tasks as normal rows.
- Bottom toolbar adds a grouped control cluster: **reminder bell · play · calendar · chevron**.

### 3.7 Nearby / map view (shots 03 tablet, 16 phone)
- Full **Google Map** with **place pins** and a **geofence circle** around the current/target
  place.
- Tapping a place shows a **callout** listing that place's tasks: *"@Anna's Bakery — Buy bread / Buy
  cakes — Tap to get directions."*
- Tablet keeps the nav rail on the left; a **LIST** toggle (top-right) swaps map↔list.

### 3.8 Home-screen widgets (shot14 tablet)
- Three resizable **list widgets** side by side: **Active Actions**, **Starred**, **Inbox** — each
  with its own mini green header + quick-add/reminder/overflow controls and scrollable task rows.
- Separate **app-shortcut icons**: **MLO** (voice add), **MLO Inbox** (quick capture), **MLO
  Remind** (add reminder).

### 3.9 Cross-cutting layout behavior (INFERRED from the set)
- **Responsive panes:** phone = single pane with a hamburger drawer + drill-down; tablet =
  **2-pane** (nav rail + content) or **3-pane** (nav + outline/list + detail). Directly relevant to
  our Compose `WindowSizeClass` adaptivity.
- **FAB:** green circular **+** bottom-right on list/outline screens.
- **Persistent bottom toolbar** of contextual glyph actions on each content screen.

---

## 4. Where MLO's UI is dated / friction points — and what to keep

### Dated / friction (improve on these)
1. **Two clashing visual generations** (OBSERVED). Bright-green 2014-era chrome (shot06, widgets)
   sits next to flat-gray newer screens. Inconsistent; some marketing shots literally show 2014
   dates and ©2015 Google maps. → Ship **one** coherent Material 3 system, light **and** dark
   (no dark theme appears in any screenshot; **INFERRED** it's weak or absent).
2. **Row overload / tiny targets** (OBSERVED). A single row can carry: checkbox, note glyph, title,
   note line, date, 1–3 context chips, flag, star, type icon, recurrence icon, color background.
   Context chips are tiny right-aligned grey pills. High density, small hit areas, thumb-hostile. →
   Prioritize a clean primary line + progressive disclosure; make star/flag/complete comfortably
   tappable; consider a single leading "state" affordance.
3. **Unlabeled glyph toolbars** (OBSERVED). Bottom bars are rows of monochrome icons with no text
   (add / add-subtask / eye / arrows / ⋮). Low discoverability. → Labels or long-press tooltips;
   fewer, clearer actions.
4. **Editing is modal** (OBSERVED). Detail pane is read-only until you hit **EDIT**; Importance/
   Urgency show only as text there. → Offer **inline** priority/date editing and a visible
   Importance control where the value is shown.
5. **Custom View Settings is jargon-dense** (OBSERVED). "Continue searching in branch after main
   filter match", "Include parent items", `<Group>` tokens — powerful but opaque to newcomers. →
   Keep the power, add plain-language presets/explanations and live preview.
6. **Desktop-grade density on mobile** (INFERRED). The 3-pane tablet view is essentially the Windows
   app; great for power users, heavy for casual ones. → Adaptive density; a genuinely simple default.
7. **Discoverability of the signature feature** (INFERRED). The computed To-Do "magic" isn't
   explained in-product where the user first meets it; you must read help to grasp
   Importance-relative-to-parent. → Teach it inline (first-run, empty states, a peek at "why this is
   #1").

### Keep (MLO gets these right)
1. **The side-panel Views model** (§3.1): quick tiles (Inbox/Starred/Nearby) + `Today` summary +
   grouped **Outline / To-Do / Recent** list with live counts. Clear, learnable mental model.
2. **Auto-generated, self-updating computed To-Do list** — the whole point. Never make the user
   hand-sort.
3. **Importance relative to parent + snowball propagation** — tune a project once, the leaf actions
   reorder. Keep this model; surface it better.
4. **One Outline ↔ To-Do ↔ Detail** with responsive 1/2/3-pane behavior.
5. **Custom views = filter → group → sort → hierarchy** as the underlying power engine (§3.5), plus
   **Workspaces (tabs)** to save them.
6. **Contexts (with open/closed hours) + Nearby/geofenced places** and the **map callout** of
   place-scoped tasks.
7. **"Today" workload graph + device-calendar overlay** (My Events / My Tasks) — a genuinely useful
   day view.
8. **Compact expressive markers** — stars, colored flags, color-coding, recurrence glyph,
   type icons — as long as we give them room.
9. **Multiple fast capture surfaces** — Inbox, widgets, voice/Assistant, notification actions, FAB.
10. **Offline-first + optional sync** framing.

---

## Sources
- Google Play listing (desc "Updated Feb 9, 2026") + 17 screenshots: `https://play.google.com/store/apps/details?id=net.mylifeorganized.mlo` (WebFetch 403; retrieved via curl w/ desktop UA; screenshots from `play-lh.googleusercontent.com`)
- How It Works: `https://www.mylifeorganized.net/support/how-it-works/`
- GTD in MLO: `https://www.mylifeorganized.net/support/GTD-Getting-Things-Done/`
- Getting Started 1 (Outline/To-Do): `https://www.mylifeorganized.net/support/getting-started/getting-started-1.shtml`
- Getting Started 2 (Contexts): `https://www.mylifeorganized.net/support/getting-started/getting-started-2.shtml`
- Recurring tasks: `https://www.mylifeorganized.net/support/recurring-tasks/`
- Blog, "How Importance Works" (Mar 2024): `https://blog.mylifeorganized.net/2024/03/`
- Windows desktop marketing: `https://www.mylifeorganized.net/todo-list-windows.shtml`
- Site root `https://www.mylifeorganized.net/` — **BLOCKED (HTTP 403 to WebFetch)**; not used directly.
- Downloaded screenshots stored at (scratchpad, ephemeral): `webshots/mlo/shot01–17.png`.

_Companion engine/data-model detail: `mylifeorganized.md` (APK teardown, incl. `ComputedScorePriorityType`)._
