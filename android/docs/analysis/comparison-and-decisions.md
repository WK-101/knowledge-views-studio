# MLO vs TickTick — Comparison, Verdict, and Product Decisions

Companion to `mylifeorganized.md` and `ticktick.md`. This is the doc we build the
product from: it says **who excels where** and **what we therefore decide to do**.

One-line thesis: **MyLifeOrganized owns the engine; TickTick owns the experience.**
Both are online, account-bound, and closed about your data. Our wedge is to fuse
MLO's engine with TickTick's experience and make the whole thing **local-first,
offline, private, free, and losslessly portable**.

---

## 1. Verdict table — who excels, and what we do

| Dimension | MyLifeOrganized | TickTick | Winner | Our decision |
|---|---|---|---|---|
| **Task hierarchy** | True outliner, unlimited nesting, ordering, complete-in-order, collapse state | Flat checklist items (free) or **1 level** of subtasks (**premium**) | **MLO** | Adopt MLO: unlimited outline is our spine and our differentiator vs TickTick |
| **Prioritization** | **Computed Score** (importance×urgency×due proximity×ancestor inheritance) → auto-ranked To‑Do list | Manual priority (None/Low/Med/High) + Eisenhower **Matrix** (premium) | **MLO** (uniquely) | Adopt MLO computed engine; also offer TickTick's simple priority + a free Matrix view |
| **GTD contexts** | First-class: M:N, hierarchical, geo + open-hours, availability-gates the To‑Do list | Tags only (no availability logic) | **MLO** | Adopt contexts as an availability-aware layer on top of tags |
| **Dependencies** | Task→task predecessors (AND/OR), hides blocked tasks | None | **MLO** | Adopt: dependencies suppress blocked items from the "Do next" list |
| **Views** | Outline, flat **To‑Do/Active Actions**, custom filter+sort views, workspaces | List, **Kanban**, **Timeline**, **Calendar** (day→year+agenda), **Matrix** | **TickTick** (breadth/UX) | Adopt TickTick's view types, powered by MLO's filter/sort engine |
| **Quick capture** | Add task + basic parse; widget | **NLP quick‑add** (inline chips) + quick ball, notif bar, text-select, clipboard, voice | **TickTick** | Adopt NLP quick-add + multiple on-device capture surfaces |
| **Scheduling** | Start/due, flexible recurrence, lead time | Start/due + **duration**, recurrence incl. **lunar**, "skip" | ~tie | Start/due/duration + rich recurrence (RRULE), skip/complete-in-order |
| **Reminders** | Multiple, time + **location** (geofence), alert settings, auto-repeat | Multiple, time + **location**, **"annoying alert"**, daily summary, full-screen intent | **TickTick** (UX) | Adopt multi + location + persistent/annoying + daily summary |
| **Focus / Pomodoro** | — | Pomodoro + stopwatch, **17 white-noise**, strict mode, stats | **TickTick** (unique) | Adopt a local Pomodoro tied to task estimates (free) |
| **Habits** | — | Streaks, goals, annual heatmap | **TickTick** (unique) | Optional module, post-MVP; free |
| **Statistics** | — | Achievement score, trends, **yearly report** | **TickTick** (unique) | Local stats from our own data, post-MVP; free |
| **Notes / attachments** | 1 Markdown note per task | Markdown + **voice + image + file**, checklist blocks | **TickTick** | Markdown note + local file/image/voice attachments (stored locally) |
| **UI / UX polish** | Dated, dense, dialog-heavy, desktop-tethered | **Material, Lottie micro-interactions, swipe, drag, bottom sheets, theming, Material You** | **TickTick** (decisively) | Adopt TickTick's interaction language wholesale (Compose + Material 3) |
| **Customization** | Color-coding rules, task-cell themes | Themes, Material You, per-list backgrounds, app icons, fonts, 3 sidebar densities | **TickTick** | Adopt theming + MLO's rule-based color-coding |
| **Offline / privacy** | Needs account for sync; Firebase + Analytics | Account-required; Firebase, GA, Ads, Facebook | **Neither** | **This is OUR headline**: no account, no network permission, no analytics |
| **Data portability** | Closed `.mlo/.mlobak/.mfv/.mlt`; no OPML/CSV/JSON | **Effectively no on-device export** (print/image only); CSV web-only, archive cloud-only | **Neither** | **OUR headline**: complete, versioned **JSON** export/import + **OPML** + **.ics**, all free and on-device |

**Summary:** TickTick wins more *rows* (breadth + polish), MLO wins the *deep* rows that
define a serious task manager (hierarchy, computed priority, contexts, dependencies).
A user who wants power picks MLO and suffers the UI; a user who wants delight picks
TickTick and hits the hierarchy ceiling + paywall + lock-in. **No one ships both.** We do.

---

## 2. Where each app is genuinely best-in-class

**MyLifeOrganized — keep these exactly:**
1. **Computed-Score To‑Do list.** Importance (1–5) and urgency (1–5) per task, inherited
   down the tree, boosted by due-date proximity and start-date gating, producing a single
   ranked "what to do now" list. This is the feature nothing else has and the reason MLO
   users stay. (See `mylifeorganized.md` §4 for the algorithm.)
2. **Outline ⇄ To‑Do duality** with zoom/hoist into any subtree.
3. **Contexts with availability** (a task only surfaces when its context is "open").
4. **Dependencies** that remove blocked work from the "do next" list.
5. **Composable filter+sort views** (AND/OR predicate trees, multi-key sort).

**TickTick — replicate the feel:**
1. **NLP quick-add** with live span highlighting + editable chips (date, priority, tag, list).
2. **Configurable bottom nav + drawer tree**, user-mappable **L/M/R swipe actions**,
   **drag-reorder** (disabled under non-manual sort).
3. **Lottie micro-interactions**: checkbox tick, staged pull-to-refresh, completion sound.
4. **Combined scheduling bottom sheet** (date + time + duration + reminder + repeat in one).
5. **Task detail as reorderable typed blocks** (title, note, checklist, attachments, subtasks).
6. **Rich reminder UX**: full-screen intent, snooze grid, per-priority tones, daily summary.
7. **View variety**: List / Calendar (day→month + agenda) / Matrix / Kanban / Timeline.

---

## 3. Product decisions (the rules we build by)

1. **Engine = MLO, Shell = TickTick.** The domain/logic layer implements MLO's model and
   computed priority; the presentation layer implements TickTick's interaction design in
   Jetpack Compose + Material 3.
2. **Local-first and offline by construction.** SQLite (Room) only. **No `INTERNET`
   permission**, no account, no telemetry, no crash-reporting SDK. (Our skeleton already
   ships with zero network permission — we keep it that way.)
3. **Everything free.** TickTick paywalls Matrix, calendar/timeline views, custom filters,
   custom swipes, task duration/estimates, and — remarkably — **subtasks**. Every one of
   those is computable on-device, so we gate **nothing**. Removing the paywall is itself a
   feature.
4. **Lossless portability is a first-class feature, not an afterthought.**
   - **Canonical format: versioned JSON** capturing every field of every entity + tree
     order + view definitions + settings → guaranteed round-trip with no loss.
   - **Interop exports:** **OPML** (outline into other outliners), **.ics** (dated tasks
     into any calendar).
   - **Import:** our JSON (full), OPML (structure); later, best-effort importers for
     TickTick/Todoist/Google Tasks CSV/JSON so users can escape those apps into ours.
   - Export/import run **on-device**, to a user-chosen file (Storage Access Framework).
5. **Reminders/alarms** via `AlarmManager` exact alarms + notifications, fully local; no push.
6. **Optionality of TickTick extras.** Pomodoro, Habits, Statistics are modules we add
   after the core is solid; each is local-only and free.

---

## 4. What we deliberately do NOT copy

- Cloud sync, accounts, sharing/collaboration, team assignees (breaks offline/private; can
  revisit later as *optional* end-to-end-encrypted or file-based sync — never required).
- Analytics, ads, crash SDKs, remote config, A/B frameworks.
- Premium tiers / billing.
- OEM auto-start/background hacks beyond standard exact-alarm + notification permissions
  (we'll surface the standard battery-optimization prompt only if reminders need it).
- Student timetable OCR, social/"recommend", ML Kit features — out of scope for a personal
  private organizer.

---

## 5. Minimum lossless-parity schema checklist (from MLO)

Any export/import MUST preserve, per task: stable UID; parent + sibling order; title; note
(Markdown); completed + completion date; importance (1–5); urgency (1–5); start date; due
date; duration/effort + estimate min/max; lead-time; two "hide from To‑Do" flags; star +
star date; flag; goal flag; project status/completion; review cadence; recurrence rule;
reminders (each: time/offset/location/alert settings); contexts (M:N); dependencies
(predecessor UIDs + AND/OR + postpone); color-coding; collapse/group state. Plus, globally:
contexts (hierarchy, geo, open-hours), color-coding rules, saved views (filter tree + sort),
and settings. This list is the acceptance test for "no loss of data."
