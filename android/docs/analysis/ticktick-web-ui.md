# TickTick — Marketed Features & UI/Visual Reference

Research capture of TickTick's product surface, compiled from the marketing site
(ticktick.com), the Google Play listing (`com.ticktick.task`), the help center
(help.ticktick.com), and third‑party pricing write‑ups. Every detail is tagged:

- **[OBSERVED]** — seen directly in a screenshot I viewed, or stated verbatim on a page I fetched.
- **[INFERRED]** — reasonable conclusion from marketing copy / third‑party sources, not confirmed pixel‑by‑pixel.

Screenshot assets saved to the scratchpad (`webshots/tt/shot_*.png`); the Play
listing screenshots were the primary visual source. Where a claim rests on a
specific screenshot it is cited as `shot_NN`.

---

## 1. Marketed Feature Set

### 1.1 Headline positioning
- **[OBSERVED]** Play listing hero: **"All‑in‑One Productivity Partner"**, badged **Editors' Choice** and **4.8★ / 277k ratings** (`shot_20`).
- **[OBSERVED]** Site taglines pair each feature with a short promise (e.g. To‑Do List → "Organize everything in your life"; Calendar → "Easily plan your schedule"; Pomodoro → "Track time and stay focused"; Habit → "Develop and maintain good habits").
- **[OBSERVED]** Cross‑platform is a headline: **"10+ Platforms"** — iOS, Android, Windows, Linux, Chrome, Firefox, Edge (icon row), plus macOS, web, and Apple Watch / Wear OS (`shot_06`, `shot_10`).

### 1.2 Core features (each is a marketed pillar)
| Feature | Marketing line | What it does |
|---|---|---|
| **To‑Do List** | "Work, School and Personal affairs" | Central task capture; lists, subtasks, priorities, due dates. **[OBSERVED]** `shot_04` |
| **Calendar Views** | "Multiple views, Clear at a glance" | Year / Month / Week / 3‑Day / Day / Agenda(List) views; task chips on days; hourly timeline. **[OBSERVED]** `shot_15`, `shot_18` |
| **Pomodoro / Focus** | "More concentrated, More efficient" | 25‑min focus timer + Stopwatch mode, white‑noise sounds, focus statistics. **[OBSERVED]** `shot_05`, `shot_09` |
| **Habit Tracker** | "Discipline equals freedom" | Daily check‑ins, streaks ("N Total Days"), quantified goals (cups, pages), habit icon library, stats. **[OBSERVED]** `shot_11`, `shot_16` |
| **Eisenhower / Priority Matrix** | "Focus on urgent & important tasks" | 2×2 urgent/important quadrants with drag between them. **[OBSERVED]** `shot_08`, `shot_19` |
| **Reminders** | "Set to never miss a thing" | Time reminders, "on time"/advance offsets, repeat rules, push notification banner. **[OBSERVED]** `shot_17` |
| **Widgets** | "Widgets" | Home‑screen widgets for tasks, habits, focus timer, water/counter, calendar. **[OBSERVED]** `shot_14` |
| **Countdown** | "Capture every important moment" | Countdown to key dates (birthdays, deadlines). **[OBSERVED]** site copy only |
| **Kanban** | "Categorize and Manage Tasks" | Board / column view of a list. **[INFERRED]** site + help copy; no screenshot viewed |
| **Timeline** | "Lighter Gantt chart" | Gantt‑style project scheduling. **[INFERRED]** help copy; no screenshot viewed |
| **Split View / Sticky Note** | — | Desktop split panes; desktop sticky notes. **[INFERRED]** help copy |

### 1.3 AI features (newer, prominently marketed)
- **[OBSERVED]** **AI Voice Add** — an "AI Mode" capture: speak/paste natural language ("At 10:00 AM I have a project meeting… Submit the weekly report before 3:00 PM and remind me 10 min in advance… Go to the gym at 7:00 PM for ~1 hour"), and it splits into discrete tasks with parsed date, time range, reminder, and list/tag ("Work", "Fitness"). Ends in a **Save** button (`shot_12`).
- **[OBSERVED]** Site also lists **Audio Summary** (recording → transcript + structured summary) and **Automated Workflows** (integration via MCP, CLI, or "OpenClaw").

### 1.4 Supporting capabilities (marketed, not all screenshotted)
- **[OBSERVED]** Natural‑language date parsing on quick add; repeat/recurring tasks; custom filters; keyboard shortcuts; task collaboration/sharing; calendar subscriptions; progress statistics; **40+ themes**.
- **[OBSERVED]** Third‑party integrations called out: Notion, calendar (Google / iCloud / Exchange / Outlook — logos shown in `shot_15`).

---

## 2. Visual / UI Specification (per screen actually viewed)

### 2.1 Global design language **[OBSERVED across all shots]**
- **Primary accent:** TickTick periwinkle **blue** (~`#4772F9`). Used for selected tab, FAB, links, primary buttons, "Today" pill, time stamps.
- **Contextual accent recoloring:** Focus/Pomodoro screens shift to **green**; Reminder screen to **red/coral**; each feature's promo uses a tinted background of its accent. So accent is *feature‑scoped*, not globally fixed.
- **Surfaces:** very light gray app background (~`#F5F6F8`), pure‑white content **cards** with generous corner radius (~16–20px) and soft, low‑contrast shadows. Lots of whitespace.
- **Typography:** heavy bold sans for titles/headlines; medium‑weight row titles; muted gray for secondary metadata and section labels (ALL‑CAPS small labels like `TODAY`, `HIGH PRIORITY`).
- **Checkboxes:** rounded‑square outline, stroked in the task's **priority color**.
- **Icons:** thin line icons for chrome; **colorful filled emoji‑style circular icons** for lists and habits.

### 2.2 Task list screen (mobile) **[OBSERVED `shot_04`, `shot_20`]**
- **Top bar:** hamburger (☰, opens side drawer) at left; kebab (⋮, list options) at right; large **list title** on its own line below the bar (e.g. "Inbox", "Next 7 Days"). No center title — title is left‑aligned and oversized.
- **Grouping:** tasks grouped under collapsible section headers showing **label + count + chevron** (e.g. `TODAY  4 ⌄`, `HIGH PRIORITY  3 ⌄`). Groupable by date bucket or by priority.
- **Task row:** `[checkbox] Title …………………… [right metadata]`. Right side carries **time in blue** (`07:00`), or relative date (`Today`, `Tomorrow`, `Jul 19`), plus small **recurring** (↻) and **alarm/reminder** (⏰) glyphs when set.
- **Priority coloring:** High = red checkbox + red left accent bar on the group; Medium = amber/yellow; None = gray. (`shot_04`)

### 2.3 Bottom navigation + FAB (mobile) **[OBSERVED `shot_06`, `shot_10`]**
- Persistent **bottom tab bar**, ~5 items: **Tasks** (checkbox, selected = blue), **Calendar**, **Matrix** (four‑squares / grid), **Pomo/Focus** (target/pie), **More** (⋯).
- A **blue circular FAB with `+`** floats at the bottom‑right, overlapping the tab bar — the primary quick‑add entry.

### 2.4 Side drawer / navigation (desktop, mirrors mobile drawer) **[OBSERVED `shot_06`]**
Desktop is a **3‑pane layout** with a far‑left icon rail:
- **Far‑left icon rail (module switcher):** Tasks (✓, selected), Calendar, Focus/Pomo (target), Matrix (four‑squares), a clock/timeline module, Habit, Search, then utility icons (sync ↻, notifications 🔔, help ?).
- **Second pane (smart lists + lists):** fixed smart lists **Today (11)**, **Next 7 Days (24)**, **Inbox (9)** with per‑list badge counts; a **"Lists"** header; user lists each with a color/emoji icon and count — e.g. *September Plan*, *Work Hard (64)*, *Life Memo (7, blue unread dot)*, *Mindful Living (7)*, *Workout Plan (1)*, *Wishlist*; then **Completed** and **Trash** at the bottom.
- **Third pane:** the open list with an inline **"+ Add task"** field at top and grouped tasks.
- **Detail pane (right):** selected task detail with a date/time bar, title, and description area.

### 2.5 Quick add **[OBSERVED partial / INFERRED]**
- **[OBSERVED]** Desktop has an inline **"+ Add task"** field at the top of each list (`shot_06`); mobile uses the blue **FAB**.
- **[OBSERVED]** The AI/voice add composer parses NL text into date + time‑range + reminder + list (`shot_12`).
- **[INFERRED]** The standard quick‑add bar exposes shortcut buttons for **date, priority, list, tag, reminder** (standard TickTick behavior; the exact button row was not captured in a viewed screenshot).

### 2.6 Calendar **[OBSERVED `shot_15`, `shot_18`]**
- **Month view:** 7‑column grid, weekday header, day numbers; each day stacks **colored task/event chips** (color = source list or calendar account). Top‑right has a **grid/layout toggle** and a **kebab** menu; the current day number sits in a **blue filled circle**.
- **Day / Agenda view:** a horizontal **week strip** (Mon–Sun with the selected day highlighted blue and small event dots under days that have items); below it either a grouped **agenda list** (`TODAY`, `NOTE` sections) or an **hourly timeline** (07:00, 08:00 … left gutter) with event blocks showing time range + title (+ subtitle note like "eggs, milk, bread"). Completed items show a **filled check circle** in the timeline gutter; upcoming show an empty circle.
- **Account integration:** Google, iCloud, Exchange, Outlook logos shown as connectable calendar sources (`shot_15`).

### 2.7 Pomodoro / Focus **[OBSERVED `shot_05`, `shot_09`]**
- Top tab toggle **"Pomo" | "Stopwatch"** (underline indicator on active); top‑right a **stats/pie** icon and a **`+`**.
- Centered **"Focus ⌄"** selector (choose what you're focusing on / task link).
- Large **circular ring timer** reading `25:00`; ring fills with progress (green in `shot_09`). Primary **"Start"** button (blue pill).
- **White‑noise picker** (bottom sheet): grid of circular sound tiles — *None* (selected), Clock, Boiling, Wooden fish, Rain, Cafe, Morning, Summer, Forest, Stream, Wave, Desert, Music… (`shot_05`).
- A **floating mini‑timer** (black rounded pill, `25:00`) — picture‑in‑picture while the timer runs (`shot_05`).

### 2.8 Habit tracker **[OBSERVED `shot_11`, `shot_16`]**
- Header **"Habit"** + top‑right icons: **stats (pie)**, a **card/flip layout** toggle, and a **list/filter** icon.
- **Week date strip** (Wed…Tue) with the selected/today day in a **blue filled circle**.
- Section header with count (`Others  6 ⌄`).
- **Habit row:** colored circular emoji icon + habit name + optional quantified subtitle (`0 / 3 Cups`, `0 / 20 Pages`) on the left; **streak count** `N` over "Total Days" on the right. Examples: Exercise, Drink Water, Read, Early to Rise, Eat Fruits, Daily Check‑in, Track Expenses, Early to Bed.
- **Icon library** shown as a large grid of pastel circular emoji icons (habit customization).
- **Swipe gesture:** swiping a habit row left→right reveals a **green check** to log the day (`shot_16`).

### 2.9 Eisenhower / Priority Matrix **[OBSERVED `shot_08`, `shot_19`]**
- **2×2 quadrant grid** on a faint graph‑paper background; a light‑blue vertical **↑ arrow (importance)** and horizontal **→ arrow (urgency)** cross the center.
- Quadrant color coding + Roman numerals:
  - **I — Urgent & Important** → red badge.
  - **II — Not Urgent & Important** → orange/amber badge.
  - **III — Urgent & Unimportant** → blue badge.
  - **IV — Not Urgent & Unimportant** → green badge.
- Each quadrant is a white rounded card holding a checklist of tasks (with dates on the dashboard variant, `shot_19`). Tasks are draggable between quadrants. **[INFERRED: drag]**

### 2.10 Reminder / date picker **[OBSERVED `shot_17`]**
- **Push notification banner:** TickTick logo, app name, task title ("Product Plan"), timestamp ("11:00 am").
- **Date picker sheet:** tabs **"Date" | "Duration"**; a month grid with the selected day in a **pink/coral filled circle**; then rows: **Set Time** (`15:00`), **Set Reminder** (`On time`), **Set Repeat** (`No Repeat`) — each with a trailing value and clear (×) / chevron. Coral accent throughout this flow.

### 2.11 Widgets (home screen) **[OBSERVED `shot_14`]**
Distinct widget types shown:
- **Habit progress ring** (single habit, e.g. "Eat Fruit — 2 Days", with M–S dots rendering **done ✓ / missed ✗ / skipped(hatched) / in‑progress(ring)** states).
- **Focus timer widget** ("Learn new words 25:00" + green play button).
- **Habit list widget** (Read 74 Days, Do housework 148 Days).
- **Today task widget** ("Today 10", checklist rows + inline `+`).
- **Water / counter widget** ("Drink Water 200/1000 ml" + weekday progress circles).
- **Calendar widget** (mini month + day's timed task list).

### 2.12 Multi‑device **[OBSERVED `shot_06`, `shot_10`]**
- Desktop 3‑pane, tablet 2‑pane, phone single‑pane, and **Apple Watch** face showing a compact task list ("Little park cafe", "Ticket Office") with ☰ / `+` / focus controls.

---

## 3. Polish / Interaction Details Worth Copying **[OBSERVED unless noted]**
- **Count badges everywhere** — smart lists and every group header show a live item count; groups collapse via chevron.
- **Relative dates** rendered in words (`Today`, `Tomorrow`) and **times in accent blue**; overdue/scheduled differentiated by color.
- **Priority as color** carried consistently across checkbox stroke, group accent bar, and matrix quadrant.
- **Feature‑scoped accent theming** (blue tasks / green focus / coral reminders) gives each module its own identity while sharing one layout grammar.
- **Emoji‑led list & habit icons** for fast visual scanning and personality.
- **Completion affordances**: filled check‑circle in timeline gutter; swipe‑to‑complete on habits; ring‑fill progress on timers and habit widgets.
- **Floating mini‑timer (PiP)** keeps focus session visible outside the app.
- **Inline "+ Add task"** at the top of lists (desktop) + **blue FAB** (mobile) — dual quick‑capture.
- **[INFERRED]** Smart date NLP on capture (typing "tomorrow 3pm" auto‑sets due date), consistent with the voice‑add parsing shown.

---

## 4. Free vs Premium Gating

**Price: [OBSERVED via third‑party sources]** ~**$35.99 / year** (~$3–4 / month); single premium tier, no per‑seat/business tiers.

> The official pricing page (ticktick.com/pricing) returned 404 during capture, so the split below is drawn from third‑party pricing write‑ups (Larksuite, TheDigitalPM, AIToolPick) and TickTick's own blog. Numeric limits vary slightly between sources — treat exact numbers as **[INFERRED]**.

**Free**
- Create tasks, checklists/subtasks, due dates, priorities, reminders.
- **List view only** of the calendar.
- Basic habit tracking (**~5 habits**, no advanced statistics).
- Standard themes; cross‑platform sync.
- Limited collaboration/sharing.
- **[INFERRED] limits:** ~**9 lists**, ~**99 tasks per list**, ~**19 subtasks per task**, **2 reminders per task**, limited shared members.

**Premium (unlocks)**
- **Unlimited** tasks, lists, subtasks.
- **All calendar views** (Month / Week / 3‑Day / Day) — only List is free.
- **Custom Filters** (the single biggest premium differentiator) & advanced smart lists.
- **Kanban** board view.
- **Calendar subscriptions** + full 2‑way calendar sync.
- **Habit statistics / trends**.
- **Location‑based reminders**; more reminders per task.
- **Custom themes** (free = standard set only).
- **Task templates**.
- **Activity/completion statistics**; more collaborators per shared list.

---

## 5. Source Index
- Marketing: https://ticktick.com/ , feature taglines.
- Play Store listing: `com.ticktick.task` (Editors' Choice, 4.8★/277k). Screenshots `shot_04,05,06,08,09,10,11,12,14,15,16,17,18,19,20` archived in scratchpad.
- Help center: https://help.ticktick.com/ (feature guide list).
- Pricing (third‑party): larksuite.com/blog/ticktick-pricing, thedigitalprojectmanager.com/tools/ticktick-pricing, aitoolpick.org, ellieplanner.com. Official /pricing was 404 at capture time.
