# Track UX: information architecture, settings, menus and flows (round 3, Parley 6.2.1)

*Read-only, code-based audit of `a67fd3d6` (6.2.1). Paths are relative to `parley-phone/`. Round 2's UX track ([round2/UX.md](../round2/UX.md), plan items U1–U13) shipped mostly in 5.6. This report doesn't repeat items that are fixed. It reports what is still open, what regressed, and what the 6.0–6.2.1 features (Situations, Recall, Case files, Family shield, Chapters, Archive, Agenda, Rescue call, network names, names in their language) added.*

**Severity:** **H** hurts many users or breaks a stated rule. **M** is real friction or inconsistency. **L** is polish.
**Effort:** **S** is less than a day. **M** is 1–3 days. **L** is more than 3 days.

House constraints that every recommendation respects:
- no new permission;
- at most 147 settings (148 with the network-name toggle);
- menus of 7 items or fewer;
- English only;
- offline;
- no NFC or video calling.

---

## 0. Summary

**What is good. Keep it, and don't let the plan break it.**
- **One menu builder.** `core/common/.../ux/Menus.kt` builds the contact ⋮, the selection ⋮ and the Recents sheet from plain facts, with `MENU_LIMIT = 7` held by a test. The three menus that broke the rule in round 2 now comply.
- **One pre-call gate.** `CallGate` and `DialGuardSheet` combine the confirm question, the SIM choice, pocket guard and call-time warnings into one sheet (`AppViewModel.kt:344-372`). Making a call never stacks dialogs.
- **One block flow.** Block now asks first and offers Undo. Wherever a number shows, the action flips to Unblock: `Menus.kt:69,155`, `PostCallCard.kt:136-141`, and the selection bar with its confirmation and Undo (`BlockingHooks.kt:194-215`).
- **The design kit held.** New features did not invent their own look:
  - The 6.x screens (cases, situations, recall, chapters, archive, shared labels, agenda) contain **zero** raw `Card`, hex colours or `fontSize` overrides.
  - The private mark is one token set (`core/ui/.../PrivateBadgeTokens.kt`).
  - All 193 `toast()` calls go through one snackbar pipeline (`AppViewModel.kt:201`, `ParleyRoot.kt:137-151`).
- **Motion respects "reduce motion".** Every infinite animation, including the ringing frame, the answer-slider shimmer and the nudge, is guarded by `ParleyMotion.reducedMotion()` (`CallerHeader.kt:232`, `IncomingControls.kt:432,455`).
- **Advanced folds are consistent.** Each Settings page has an Advanced fold. A test keeps the catalog and the pages in step (`SettingsCatalog.ADVANCED`). There are 12 or fewer basic rows per page.

**What is wrong now.** Growth has outrun the IA again: 6.0–6.2 added eight headline features, and *none* of them was given a home in Tools. Three were placed where few people will look.

1. **The newest signature features are almost undiscoverable.**
   - Situations ("one tap") has no Tools row, and its only in-app home is three levels down: Settings › Calls › Situations.
   - Recall, Case files, Archive, Chapters, Family shield and Agenda have no Tools row either.
   - The What's new card is generic, so the 6.0 and 6.1 updates told users nothing (U-01).
2. **Overlapping models confuse "who may ring".** Situations › Night, Blocking › Quiet nights (off hours) and the drive profile all write the same switches. None of the affected screens says a Situation is in control (U-02).
3. **Moment-of-use surfaces are overloaded or too deep.**
   - The post-call card shows 10 buttons of equal weight on a screen that times out (U-03).
   - Recents row actions take long-press → ⋮ → More…. Selecting several calls can only block them, not delete them (U-04).
4. **Concrete breakages.**
   - The empty state of a label page points to "Add to label", a menu item that no longer exists, and a label page has no way to add people at all (U-05).
   - Two settings control "notes on the lock screen" (U-06).
   - Two different lock icons sit side by side in the Contacts header (U-08).
   - The A–Z rail is invisible but swallows taps on the right half of the row Call button (U-09).

---

## 1. Inventory (measured)

| Thing | Now | Round 2 | Source |
|---|---|---|---|
| Settings counted by the budget | **147** (84 page + 63 elsewhere), plus 16 links | 164 | `SettingsCatalog.kt` (`SETTINGS_CEILING = 147`, `SettingsSearchTest.kt:169`) |
| …of which are not stored preferences (links to screens, external pages, one-off actions, info) | **≈48** (see §3.1) | – | catalog |
| Settings root rows | Tools + 12 categories + Reminders = **14** | 14 | `SettingsScreen.kt:143-148` |
| Tools rows / featured (shown before "n more") | **57 / 23** in 7 jobs | 46 / 22 | `Capabilities.kt:84-224` |
| Compose destinations | 71 `composable` routes, plus the call screen | ~63 | grep |
| Dialogs, sheets, dropdowns | 108 `ConfirmDialog`, 87 `ParleyDialog`, 25 `ParleySheet`, 36 `DropdownMenu` | – | grep |
| Undo offers | 15 `offerUndo` plus the `UiEvent.Undo*` paths | ~8 | grep |
| Quick Settings tiles | 5 (Private, Message a number, Scan QR, Expecting a call, **Situation**). The app never offers to add one: there is no `requestAddTileService` | 4 | `AndroidManifest.xml:438-503` |
| Launcher shortcuts | Message a number, Scan QR, **Rescue call**, New contact plus pinned favourites | – | `res/xml/shortcuts.xml`, `Shortcuts.kt:130-140` |

---

## 2. Information architecture

### 2.1 Where each 6.x feature lives today

Entry points counted: tabs, headers, ⋮ menus, Tools, Settings rows, screens, notifications, tiles and shortcuts (not search).

| Feature | Entry points today | n | Verdict |
|---|---|---|---|
| **Situations** (6.0) | Settings › Calls › Situations (`CallsPages.kt:196-226`); QS tile "Situation" (never offered); home chip **only while one is on** (`HomeScreen.kt:241`) | 1 permanent | **Badly buried** (U-01) |
| **Recall / Search everything** (6.0) | Contacts search chip, shown only while text is typed (`ContactsChips.kt:61`); runs automatically when no contact matches | 1 | **Wrong tab**: the question "who called in March" starts in Recents (U-07) |
| **Case files** (6.1) | Card on an organisation's contact or number page (`ContactDetailScreen.kt:~364`); contact ⋮ › Keep a case file (`Menus.kt:76`); Recall | 2 | No list of all cases (U-12) |
| **Family spam shield** (6.1) | Label page › shared label section › switch (`FamilyShieldScreen.kt:94-108`) | 1 | Absent from Blocking & screening, where people decide what rings (U-11) |
| **Dead-number radar** (6.1) | Contact health check section | 1 | Fine (an attribute of Health) |
| **Chapters** (6.2) | Label page, **first row** (`LabelScreens.kt:494`); end-of-chapter notification | 1 | Too prominent on every label (U-10) |
| **Archive** (6.2) | Contact ⋮ › **Privacy…** › Archive (`Menus.kt:70-74`); Contacts ⋮ › Archived, only once something is archived (`HomeScreen.kt:365-366`) | 2 | Placed under "Privacy" although the glossary says it's *not* private; not offered at Delete (U-13) |
| **Agenda** (6.2) | Contact page note section; number page; Recall long-press; share target; in-call More; after-call card | 6 | Good placement; model overlaps promises (§7) |
| **Rescue call** (6.2) | Tools; Settings › Calls › Situations; launcher shortcut; long-press on the Situation tile | 4 | Good |
| **Network name** (6.2.1) | Recents, number page, notification, Recall, Save | – | Good: one tag, spoken by TalkBack (`NetworkNameUi.kt:22-42`) |
| **Name in their language** (6.2) | Editor "Add" chip, among 18 others (`ContactEditScreen.kt:1006-1029`); "Add an English spelling" | 1 | Fine; the icon duplicates Languages (U-17) |

### 2.2 Prominent but rarely needed

| Thing | Where | Why it's wrong |
|---|---|---|
| **"Keep a case file"** as a top-level contact ⋮ item | `Menus.kt:75-76`: shown for *every* contact with a number unless a case already shows | Case files are for organisations. On Mum's page this takes one of 7 slots ahead of More…. |
| **Chapter: "Give it an end"** as the first row of every label page | `LabelScreens.kt:493-500` | It sits above the ringtone, rhythm, SIM and members. Labels are everyday; chapters are a rare life event. |
| **Labels** as a Contacts header icon **and** as a chip | `HomeScreen.kt:319`; `ContactsChips.kt:113` | Two entry points side by side; the header icon costs a scarce slot (see U-08) |
| **Drive profile** featured in Tools | `Capabilities.kt:196-197` (`.top()`) | Since 6.0 the drive profile is part of the Driving Situation. Tools features the part and lacks the whole. |
| **Label ringtone** twice on one page | Top-bar icon `LabelScreens.kt:460` and the first content row `:501-518` | Duplicate |

### 2.3 Hard to find relative to need

| Need | Path today | Taps |
|---|---|---|
| Turn on "Meeting" now | Settings (⋮ › Settings) › Calls › Situations › tap the Situation | 5, unless the tile was added by hand |
| Remind me to call someone in Recents | Long-press the row › ⋮ › More… › Remind me to call › time | 5 (`Menus.kt:165`, `RecentsTab.kt:286-288`) |
| Delete several calls | Not possible: the selection bar has only Block and a ⋮ for a single call (`BlockingHooks.kt:186-193`) | – |
| Add people to a label from that label | Not possible on the label page; go to Contacts › select › ⋮ › Edit… › Add to label | 5+ |
| Restore a Parley backup on a new phone | Not in onboarding or "Coming from…": Settings › Backup & sync › Backup & restore › Restore | 5 |
| Find "the plumber who called in March" | Recents search finds nothing; you must think of searching *Contacts* and tap "Search everything" | – |
| See all case files | No such place; Recall only | – |

---

## 3. Settings

### 3.1 The budget counts the wrong things

The 147 ceiling counts every non-`link` catalog entry. About **48** of them store no value:

- **Screens:** `blocking`, `spam_lists`, `templates`, `transfer`, `situations`, `call_helpers`, `phone_menus`, `call_time`, `sims`, `speed_dial`, `simple_mode`, `contact_page`, `my_details`, `app_lock_method`, `family_safe_word`, `privacy_dashboard`, `who_can_see`, `private_directory`, `backup`, `sync`, `journal`, `shared_labels`.
- **One-off actions:** `import_file`, `import_sim`, `export_vcf`, `export_csv`, `export_account`, `import_calls`, `clear_history`, `open_export`, `reset_tips`, `delete_all_data`, `backup_restore`, `backup_move_phone`, `shared_labels_join`, `diagnostics`, `default_dialer`.
- **External pages and info:** `default_dialer_help`, `sim_accounts`, `carrier_settings`, `app_permissions`, `notification_settings`, `battery`, `xiaomi`, `full_screen`, `version`.
- **Lists of data:** `kept_forever`.

There are roughly 99 real preferences. The ceiling therefore penalises adding a *link* as much as adding a *choice*. It also hides that the real cognitive load is the number of choices.

**Recommendation (owner decision):** mark these entries `link = true`, and keep two budgets:
- **preferences ≤ 100**, frozen at today's count;
- **rows per page ≤ 12**, already enforced.

The house ceiling of 147 still holds for the total. Effort S.

### 3.2 Findings

**U-06 [H]. Two settings decide whether notes show on the lock screen.**
- Privacy & security › Caller on the lock screen has the choice "Name and notes" (`SettingsPages.kt:625`; `LockScreenCaller.kt:53` `showsNotes`).
- Calls › During calls › Advanced has "Notes on the lock screen" (`memory_lock_screen`, `CircleSettings.kt:92`).
- `AgendaBridge.kt:30` ORs the two: `memoryOnLockScreen || lockScreenCaller.showsNotes`.
- The memory card reads only the second (`AppTelecomDependencies.kt:148`).

Impact: a privacy-minded user who sets Caller to "Name" still has notes on the lock screen if the older switch is on. The two paths may also disagree for the agenda and the memory card.

→ Remove `memory_lock_screen` and migrate `true` to `NAME_AND_NOTES`, only when the caller setting is `NAME`. This frees one setting, which is exactly the slot the network-name toggle needs. Effort S.

**U-14 [M]. The Blocking & spam *page* duplicates the Blocking & screening *screen*.**
- The page's basic group is: a link to the screen, `repeat_callers` and `expecting_call` (`SettingsPages.kt:318-324`).
- Both switches are also on the screen (`BlockingScreen.kt:191-208, 307-320`).
- Both only matter when unknown callers are silenced, yet they show for a default user whom everyone can ring.

→ Make the root row "Blocking & spam" open the Blocking & screening screen directly. Fold the page's Advanced rows (Sales lines, hints from notes, Spam lists, Rule templates, Import & share) into the screen's existing Advanced collapsibles. Grey out Repeat callers and Expecting a call with "Matters once unknown callers are silenced". This removes one level and two duplicate rows. Effort M.

**U-15 [M]. Calls › Situations is a grab bag.**
- The page holds Situations, Rescue call, Helpers, Drive profile, **Phone menus** and **Talk-time reminders & limits** (`CallsPages.kt:196-226`). Its summary reads "Driving, meeting, night and travelling; helpers, phone menus and call time".
- Phone menus belong with the keypad, and call time belongs with SIMs and plans (or During calls).

→ See the target tree below. Effort S (only `SettingPlace` and page code move; keys stay).

**U-16 [M]. Two categories are too small to deserve a root row.**
- Keypad has 5 settings (`SettingsPages.kt:294-306`). Messaging has 2: `quick_replies` and `messaged_expiry` (`:557-575`).
- Round 2 (D34) proposed dissolving both; this is still open.
- Quick replies are used at *decline time* and by Situations ("Reply offered first"), so they belong under Calls › Answering.
- `messaged_expiry` belongs on Tools › Messaged numbers, where its list is.

Effort S.

**U-18 [L]. Recents' look is set in three places.**
- `recents_layout` and `recents_style` on Recents & history (`SettingsPages.kt:533-541`).
- `recent_tap` on Layout & gestures › Advanced.
- All three again in Recents ⋮ › Recents view… (`HomeScreen.kt:345`).

→ The Settings rows should open that one dialog, as a single "Recents view" row. Effort S.

**U-19 [L]. Display toggles of one tool sit in Settings.**
- `people_card` and `first_mover` (Recents & history) only shape the People card of Call insights, and `first_mover` depends on `people_card`.

→ Merge them into one choice (Off · On · On, with who reaches out first; −1 setting) and offer it from the card's own ⋮ as well. Effort S.

**U-20 [L]. Other removal and merge candidates (each −1).**
- `csv_bom` becomes a tick box in the call-history export sheet, unstored, like "Include private contacts".
- `sim_labels`: always show the SIM when two SIMs are active (the setting already does nothing otherwise).
- `reset_tips` moves to About › "Help & tips" (round 2 D10, still open).

Effort S each.

**Per-contact or contextual candidates (still open from round 2 D15):**
- `speaker_default` should also be per contact and per label. Auto-answer and vibration already are (`CallerChoiceRows.kt`), and "Grandma on speaker" is the real need.
- Expecting a call should be offered right after an outgoing call to an unsaved number (D4).

Effort M and S.

**Dependency gaps:** these rows show even when the setting they depend on is off.
- `repeat_callers`, `expecting_call` and `expected_hints` (need unknown callers silenced).
- `pre_call_peek` and `memory_prompt` (fine), `private_history` (needs private contacts).
- The Circle ⋮ › "Circle settings" item opens Contacts scrolled to `log_prompts`, an **Advanced** row (`HomeScreen.kt:371`; `ADVANCED` contains `log_prompts`). The person asked for Circle settings and lands in a fold.

### 3.3 Target structure (progressive disclosure, keys unchanged)

The Settings root goes from 14 rows to 12. B = basic and shown; A = under Advanced on its page.

1. **Tools** (as today).
2. **Appearance.** B: Theme, Wallpaper colours, Sort by, Show names as. A: Pure black, Density, Avatars, Second line, Prefer nicknames.
3. **Layout & gestures.** B: Navigation bar, Open on, Simple mode. A: Calls layout, Favourites in Contacts, Swipe actions. (`recent_tap` → Recents view.)
4. **Calls:** Default phone app, Confirm before calling, Pocket guard, Voicemail, then five pages:
   - **Answering.** B: Answer by, Unknown ringtone, Caller photo, **Quick replies** (from Messaging). A: Background, Flip to silence, Auto-answer, Vibration, RTT.
   - **During calls.** B: Speaker, Screen at ear. A: Vibrate during calls, Power button, Note after calls, Peek before calling, **Talk-time reminders & limits** (from Situations).
   - **Keypad & dialling** (the Keypad category plus Phone menus). B: Tones, Vibration. A: Letters, Speed dial, USSD, Phone menus.
   - **SIMs & carrier** (unchanged).
   - **Situations.** Situations, Drive profile, Rescue call, Helpers. Nothing else.
5. **Blocking & spam** opens the Blocking & screening screen directly (U-14).
6. **Contacts** (unchanged).
7. **Recents & history.** B: Keep Parley's copy, Trim Android's log, Clear, **Recents view** (one row). A: Numbers kept forever, Import, People card.
8. **Privacy & security** (minus `memory_lock_screen`).
9. **Backup & sync** (unchanged).
10. **Reminders.**
11. **Notifications & device.**
12. **About**, plus Help & tips.

Net effect: −4 settings (U-06, U-19, `csv_bom`, `sim_labels`). The network-name toggle fits, with three spare.

---

## 4. Menus, sheets and chip rows

| Surface | Items | Assessment |
|---|---|---|
| Recents ⋮ | Blocking & screening, Call insights, Recents view…, Export…, Clear call history, Tools, Settings = 7 (`HomeScreen.kt:340-348`) | OK |
| Contacts ⋮ | Select all, Sort, Duplicates, [Reorder], [Archived], Tools, Settings ≤ 7 (`:349-362`) | OK |
| Contact ⋮ | Remind, Share…, Block/Unblock, Privacy…, **Keep a case file**, More…, Delete = 7 (`Menus.kt:66-90`) | Case file shouldn't hold a top slot (§2.2). **Archive under "Privacy…"** contradicts the glossary ("Not … Private"). Set ringtone and Delete automatically duplicate the page's "Settings for this contact" (`ContactSettingsSection.kt:83-96, 176-193`). |
| Contacts selection | Bar: Select all, Star, Share, ⋮ (Edit…, Message all, Share…, Merge, Privacy…, Delete) | **Two "Share"**: a bar icon (`SelectionBar.kt:189-198`) and ⋮ › Share… (Copy as text, Export .vcf). Fold them into one. |
| Recents selection | Close, "n selected", Block n, ⋮ (single only) (`BlockingHooks.kt:179-193`) | **No Delete for several calls** (U-04) |
| Recents call sheet | 4 quick buttons, then Create, Add, Block/Unblock, **Why it rang…**, More…, Delete (`Menus.kt:152-168`) | "Why it rang…" holds Always allow, Allow 24 h, Report and Search web, which are not "why" questions. It is also offered for **outgoing** calls, which never "rang" (`Facts` has no direction). Create contact and Add to contact share the **PersonAdd** icon (`RecentsTab.kt:651-652`; the post-call card uses PersonSearch for Add, `PostCallCard.kt:129`). |
| Post-call card (unknown number) | Up to **10 equal tonal buttons** plus "Block this range?" (`PostCallCard.kt:126-150`) | Breaks the 7 rule in spirit; no hierarchy (U-03) |
| In-call More | Overflow controls + Note, To talk about, Open contact, Copy, RTT, Hold mode, then Safety (Scam, Verify, Helper, Family) + call-time chips (`CallTimeUi.kt:204-288`) | 12+ rows for an unknown caller. The safety group is now headed and last (U11 done), but the sheet exceeds 7. Acceptable as a headed sheet; at least fold Copy number into the header and RTT into its own control. |
| Label page ⋮ | Blocking rules, Share this label…, Rename, Delete (`LabelScreens.kt:462-487`) | No "Add people" anywhere on the page (U-05) |
| Contacts chip row | All, Search everything, Sort, Filters…, City, Private, Account, Unlabelled, match any/all, every label, Open label, Labels, Who's in…, Temporary (`ContactsChips.kt:57-128`) | Mixes **filter** chips (state) with **navigation** chips (Open label, Labels, Who's in, Temporary) in one scrolling row. Temporary has a chip but Archived doesn't. |

**Destructive-action safety:**
- **Good:** contact Delete, label Delete (with Undo), case "Stop keeping" and reference Delete (`CaseScreen.kt:146-155, 240`), Archive (confirm dialog), Recents delete (Undo).
- **Gap:** swipe-Delete on a **private** contact in Contacts deletes with no question and no Undo. Only a toast shows, and the sealed copy is in History & undo after an unlock (`ContactsTab.kt:228` → `AppViewModel.kt:433-435`). Device contacts get Undo on the same swipe. → Route private deletes through the same `UiEvent.Undo` (restore from the sealed copy), or confirm first. Effort S.

---

## 5. Visual and interaction consistency

**U-17 [M]. Icon collisions that carry different meanings.**

| Icon | Meanings today |
|---|---|
| `Lock` | Private badge; **Lock now** (app lock, `HomeScreen.kt:323`); **Make private** and **Share privately (encrypted QR)** in the same menu (`ContactMenuActions.kt:141,146`); Save privately (post-call); App lock; Notes on the lock screen |
| `LockOpen` | **Make visible** (`ContactMenuActions.kt:147`) *and* **Lock private contacts** (`PrivateLock.kt:45`). An open padlock that *locks* reads backwards. |
| `Flag` | **Report** a number (`PostCallCard.kt:140`), Chapter "Give it an end" (`ChapterUi.kt:92,101`), Citizenship (`ContactEditScreen.kt:1008`) |
| `Shield` | Family spam shield, "Was it a scam?", the Privacy category, the onboarding promise |
| `Translate` | Languages *and* Name in their language (`ContactEditScreen.kt:1006-1007`) |
| `PersonAdd` | Create contact *and* Add to contact (Recents sheet) |
| `VisibilityOff` / `PhoneLocked` | Hide screen content and Hide private contacts; Private call history and Private names in other apps (`SettingsPages.kt:633-644`) |

→ Recommended assignments:
- `EncryptedQr` → `QrCode2` + lock badge, or `Key`.
- Lock private contacts → `Lock` with the label "Lock private contacts"; Lock now → `PhonelinkLock` or `Shield`.
- Chapter → `EventAvailable` or `HourglassBottom`.
- Report → `Report`.
- Name in their language → `Abc` or `Badge`.
- Add to contact → `PersonSearch` everywhere.

Then put the icon of each action in **one table** (`MenuLabel` builders already exist) so that a detekt or unit test can flag duplicates within one menu. Effort S.

**U-08 [M]. The Contacts header can show 5 icons, including two different locks.**
- The icons are Search, Labels, **LockOpen** (Lock private contacts, while unlocked), **Lock** (Lock now, with the app lock on) and ⋮ (`HomeScreen.kt:316-324`).
- On a 360 dp phone with the title "Contacts" this is cramped.
- Sighted users can't tell the two padlocks apart, and one of them is a *closed* lock that means "lock everything" while the other is an *open* lock that also means "lock".

→ Drop the Labels icon: the chip row has "Labels". Make **one** lock button with a small menu when both apply ("Lock private contacts" · "Lock Parley"), or let Lock now imply locking private contacts. Effort S.

**Markers and tags: no shared component.**
- Each marker is hand-rolled: `NetworkNameTag` (outlined `Surface`), `CallCountChip`, `StatusChip` (Circle), `VariantChipView` (Private/Temporary), `WorkBadge`, `UsualTag` (plain text), `LocalTimeTag`, `StatusPill`.
- They are consistent in tokens (`ParleyShapes.tag`, scheme colours) but not in **form**:
  - "From the network" is an outlined tag;
  - "Usual" is coloured text;
  - "Private" is a tonal chip on the page and a round badge in lists;
  - **Archived has no marker at all**, because it has no page (U-13).

→ Add a `ParleyTag(text, tone = Neutral|Info|Warn, outlined)` to core/ui and move these markers onto it. Effort M.

**Empty states, loading states, errors.**
- 39 `EmptyState` uses, mostly with one action: good.
- Loading uses 22 bare `CircularProgressIndicator`s. The Contacts cold start shows the last list head (`ContactsTab.kt:121-125`): good.
- One stale empty state: `lbl_nobody` (U-05).

**Typography.** Kit styles throughout. The post-call card and the What's new card add their own `SemiBold` and `titleSmall` headings (`PostCallCard.kt:113`). Minor.

---

## 6. Accessibility

**Good:**
- TalkBack labels on chips, the A–Z rail (an adjustable slider with `setProgress`, `FastScrollRail.kt:101-113`), the network name ("name from the network"), the call badges and the Tools "n more" rows with their state.
- 48 dp chip targets in Recents (`RecentsFilterRow.kt:5`).
- Reduced motion is honoured everywhere.

**U-09 [M]. The A–Z rail swallows taps on the row's Call button.**
- The rail is a 32 dp full-height column at `CenterEnd`. It is **invisible until dragged** (`railAlpha` 0, `FastScrollRail.kt:87,99`) and consumes every pointer down (`:117-135`).
- With "Call and message buttons in the list" on and more than 30 contacts sorted by name (`ContactsTab.kt:251`), the trailing Call `IconButton` spans 16–64 dp from the edge (M3 `ListItem` end padding 16 dp). Its right half is under the rail.
- A tap there jumps the list instead of calling.

→ Pad the list's trailing content by the rail width while the rail shows. Or start the rail's gesture only on a drag or long press. Or show the rail only while scrolling, as Google Contacts does. Effort S. Verify on a device.

**Font scaling.**
- Settings root and Tools summaries are still `maxLines = 1` (`SettingsScreen.kt:215,226,236`; round 2 D29). At 200 % the description of what's inside is an ellipsis. → 2 lines when `fontScale > 1.3`. Effort S.
- The rail's letters convert px to sp, which cancels the font scale (`FastScrollRail.kt:83`). Acceptable for an index, but say so in ACCESSIBILITY.md.

**Hidden gestures.**
- Recall's "press and hold a result" to add an agenda item (`RecallSection.kt:229-233`) is a fourth meaning of long-press. The glossary rule is "long-press selects in lists; on buttons, the alternative". The gesture has an `onLongClickLabel` for TalkBack but no visible cue.
- → Give agenda-capable results a trailing ⋮ (the glossary's own pattern for lists without a selection). Effort S.

**One-handed reach.**
- Unchanged since round 2 D30: search, Tools and Settings sit at the top.
- Situations would benefit most from a reachable control. See U-01: a chip **row** above the nav bar is already the home of the "Night is on · Turn off" chip (`HomeScreen.kt:241`).

---

## 7. Key flows, end to end

**First run** (Welcome → Default app → Permissions → Set up the basics → Coming from…, `Basics.kt:10`):
- (a) **No "I'm moving from another phone with Parley"** branch. `ComingFrom` lists only Google, iPhone, Samsung, call-log CSV and three blockers (`ComingFrom.kt:20-28`). The real Parley-to-Parley move needs Backup › Restore, which also brings private contacts, Situations and safety words. In addition, "Set up the basics" runs *before* that restore and would be overwritten by it. → Add "From another phone with Parley: restore a backup" as the first row of Coming from…, and offer it before Basics when the person chooses it on the Welcome step. (U-21 [H], Effort S–M.)
- (b) The intro card on home is generic (`strings_basics.xml:22`).

**Making a call:**
- Good: one gate sheet; the pre-call peek covers the case file and agenda.
- Friction: Peek opens only from the contact page (`ContactDetailScreen.kt:~238`), not from Favourites or Recents, where most calls start. That is acceptable, but say so in the setting's summary.

**Incoming-call decision:**
- Good: the answer slider; Reply · Silence · More; More holds Remind me, Send to another number, Block & decline (`IncomingControls.kt:238-262`).
- Friction: the caller header can stack name, name in their language, pronouns, subject, number memory, rang-through line, sales-line tag, never-calls-you card, caller card, local time, agenda (first two items) and the case-file line (`CallerHeader.kt:128-150`). On a small phone the agenda and the case file compete with the slider. → Cap at 3 secondary lines while ringing, in a fixed priority: safety > who > agenda. Move the rest to after answering. Effort M.

**After-call actions** (U-03 [H]):
- Cards show in order drop > agenda > post-call > memory (`InCallScreen.kt:472-487`). That is good: one at a time.
- The unknown-number card has 10 equal `FilledTonalButton`s: Save, Add to contact, Save privately, Remind me, Message or call on…, Block, Report, Call a saved number, Ask their name, Was it a scam? (`PostCallCard.kt:126-148`). They sit on a screen that closes itself "a moment after the call" unless touched (`:104-111`).
- "Save privately" silently means *private and temporary for 7 days* (`postcall_save_title`).
- "Call a saved number" is meaningless for most unknown callers.

→ Primary row: **Save** (split: New contact · Add to a contact · Privately for 7 days), **Remind me** and **Block**. Then one "More" for Message or call on…, Report, Ask their name, Was it a scam? and Call a saved number. Show "Was it a scam?" and "Call a saved number" up front only when the call matched scam signals or claimed an organisation. Effort S–M.

**Adding or editing a contact:**
- Good: "Save to" chips with Private and Temporary (`EditorSaveTo.kt`); "unlock to save" for private contacts; "Add an English spelling".
- Friction: 18 "Add" chips with no grouping (`ContactEditScreen.kt:1006-1029`). Name in their language sits among Website and Custom field, although it belongs with the name. The editor already has a "Name details" chevron. → Offer "Name in their language" inside Name details, and group the rest into "Contact · About · Calls". Effort S.

**Finding someone:**
- Good: search across all fields, Filters, the city chip, the A–Z rail with ★, and Recall fallback when contacts give nothing.
- Friction:
  - Recall exists only in Contacts (U-07);
  - the chip row mixes navigation and filters (§4);
  - the rail conflict (U-09);
  - archived contacts don't show in Contacts search at all, by design, but no hint says "1 archived contact matches", even though Recall knows.
- → In Contacts search, when an archived contact matches, show one line "Also archived: Ana · Show". Effort S.

**Blocking:** one flow (good). Gaps:
- The family shield is not on Blocking & screening (U-11).
- The Recents selection can block several numbers, but nothing else (U-04).
- Swipe-Block skips the question that the menu Block asks, although it does offer Undo. Fine.

**Backup and restore:**
- The Backup screen is clear about what a backup holds.
- Restore is not reachable from the first run (U-21).
- `backup_reminder` lives on two pages (Backup and Reminders), which is fine as a cross-link.

**Situations:**
1. Discovery: Settings only (U-01).
2. Effect: nothing on Blocking & screening, Calls › During (speaker), the Drive profile or the SIM screen says that a Situation currently controls the value. A person who edits Off hours while Night is on sees their change kept, by the documented rules, but without being told (U-02).
3. Two "nights": the Blocking preset "Quiet nights" (`ScreeningPresets.kt:33-36`, offered in onboarding's Basics) and the Situation "Night". They differ in who rings: contacts versus favourites.

**Shared labels:** Share happens on the label page ⋮, joining from Tools, Sync or an invitation file.
- Friction: the label page can't add members (U-05), and a shared label is mostly about its members.
- The shield and chapter rows are on the label page, but members come last, after Chapter, Ringtone, Caller tune, Policy, Safe word and Shared (`LabelScreens.kt:491-536`).
- → Put members first, then "Add people", then the settings in a fold. Effort S–M.

---

## 8. Findings in rank order

| # | Finding | Sev | Effort |
|---|---|---|---|
| U-01 | **6.x features missing from Tools and What's new.** Tools has no row for Situations, Search everything, Case files, Archived, Chapters, Family shield or Agenda (`Capabilities.kt:84-224`; `AppScreen` has none of them, `:23-32`). `since` tags stop at 6.2's Rescue call, so "New in 6.0/6.1" was empty (`CapabilitiesScreen.kt:97,113-115`). The What's new card is generic (`WhatsNew.kt:78-81`; `strings_main.xml:924`). `the_hub_has_caught_up` froze at a 5.x list (`CapabilityCatalogTest.kt:65-73`), and `CapabilityRoutesTest.PART_OF_ANOTHER` excuses Case and Situation edit. → Add the rows (Situations featured in "Calls that work better", **replacing** Drive profile; Search everything in "Never lose a contact"; Case files, Family shield and Archived under "more"). Make What's new list the release's top 3 rows by name. Extend the test to a list of *features*, not screens. Offer the Situation tile with `requestAddTileService` the first time a Situation is turned on (API 33+; no permission). | H | S–M |
| U-03 | Post-call card: 10 equal buttons on a self-closing screen (§7) | H | S–M |
| U-02 | Situations vs Off hours vs Drive profile vs Quiet nights: the affected screens are unaware (§7, §9) | H | M |
| U-21 | No Parley-to-Parley restore in first run or Coming from… (§7) | H | S–M |
| U-05 | Label page: no way to add people. The empty state names a gone "Add to label" item (`strings_data.xml:1197`; `Menus.kt:97-110`). → "Add people" button that opens the multi-pick contact picker. Rewrite the string. | H | S |
| U-06 | Two lock-screen-notes settings (§3.2) | H | S |
| U-04 | Recents: Remind me 5 taps deep; selection can't delete; "Why it rang…" mislabelled and shown for outgoing calls. → Selection bar: Delete (with Undo) next to Block. Move Remind me to call from More… to the top level, replacing Edit before call, which goes into More…. Rename the group "Allow, report…" and show "Why it rang" only for incoming calls. | M | S |
| U-07 | Recall unreachable from Recents. → The Recents search empty state ("No calls match") offers "Search everything for 'plumber march'", which opens the same Recall section. Or Recents search falls back to Recall automatically, as Contacts does. | M | S–M |
| U-08 | Contacts header: 5 icons, two padlocks (§5) | M | S |
| U-09 | A–Z rail eats taps on the row Call button (§6) | M | S |
| U-10 | Chapter first on every label page; "Keep a case file" in every contact's top menu (§2.2). → Chapter in a fold after the members; Case file under More… unless the contact is a company (`isCompany`) or has a case. | M | S |
| U-11 | Family shield invisible from Blocking & screening. → A read-only row "Family spam shield: on in Family (Warn)" in the screen's Advanced, linking to the label's shield page. And a Tools row. | M | S |
| U-12 | Case files have no index. → Tools › "Case files" lists open cases (newest first), each opening `HistoryRoutes.Case`. | M | S–M |
| U-13 | Archive: under "Privacy…"; no page (a tap opens the first number's history, `ArchiveActions.kt:165-175`); not offered at Delete (`ContactDialogHost.kt:229-247`). → Move Archive into the Delete confirmation as a secondary button: "Archive instead: keeps naming their calls". Rename the ⋮ group "Keep or hide…". Let an archived contact open a read-only page with Unarchive. | M | M |
| U-14 | Blocking & spam page duplicates the screen (§3.2) | M | M |
| U-15/16 | Situations page grab bag; Keypad and Messaging root rows (§3.2–3.3) | M | S |
| U-17 | Icon collisions (§5) | M | S |
| U-22 | Agenda vs promises vs To call: three follow-up lists with three surfaces (§9) | M | M |
| U-18/19/20 | Recents view in 3 places; People card settings; small removals (§3.2) | L | S |
| U-23 | Settings summaries `maxLines = 1` at large fonts (§6) | L | S |
| U-24 | Private-contact swipe delete without Undo (§4) | L | S |
| U-25 | No `ParleyTag` in core/ui (§5) | L | M |

---

## 9. Consolidation: what to merge and what to keep apart

| Pair | Verdict | Reasoning |
|---|---|---|
| **Situations ↔ Off hours ↔ Quiet nights preset ↔ Drive profile** | **Partial merge.** | Off hours is the mechanism, and should stay as the one engine on Blocking & screening. The *scheduled* "Quiet nights" preset should **become** the Night Situation with a 22–07 window, so there is one night concept, set in one place. Blocking & screening then shows "Night (Situation) · 22:00–07:00 · Edit". The Drive profile stays a device registry (which Bluetooth is "my car"), shown only inside Driving › Your car, and its Tools row goes. Every page whose value a Situation is overriding shows one shared banner, "Set by Meeting · Turn off" (new `SituationOverrideBanner` in app/ui, used in Blocking, During calls, Drive and SIM). Effort M–L. Don't merge Situations into Android's Modes: offline and no new permission rule out reading DND rules. |
| **Agenda ↔ Promises ↔ To call ↔ Case-file promises** | **Merge the presentation, keep the storage.** | Agenda is already stored as `[ ]` promise lines in the note (GLOSSARY "To talk about"). Promises show in Stay in touch (`ContactAboutSections.kt:79`); the agenda shows in the Note section (`:222-283`); To call is in Recents; case promises are in the case. For a person these are one question: "what's open with Ana?" → One **"Next time with Ana"** card on the contact page and caller header: Call back (To call item) · To talk about · You promised, each ticked the same way. Keep To call as the Recents strip (a *calls* list) and case files separate (they're *organisation* timelines). Effort M. |
| **Archive ↔ Private ↔ Temporary** | **Keep three variants; unify the control.** | They answer different questions: who can see it (Private), whether it's in my lists (Archive) and how long it stays (Temporary). Merging would blur "locked" with "out of the way", the confusion the glossary already guards against. Instead, add one row in Settings for this contact: **Kept as: Visible · Private · Archived** (exclusive), with **Delete automatically…** as its own row. Remove Privacy… from the ⋮ once that row exists. Effort M. |
| **Recall ↔ Contacts search** | **Keep Recall as a mode of search, and make it global.** | A separate Recall screen would add a place; the current fallback-plus-chip design is right. The gap is that it's only on one tab (U-07). Make "Search everything" available from every tab's search, Recents first. Don't add a tab. |
| **Family shield ↔ Block lists ↔ Reputation (sales lines) ↔ Spam lists** | **Keep separate engines; one explanation surface.** | They differ in source (family, you, your calls, public lists) and trust, and "Why it rang/was blocked" already names the source. Merging the controls would hide where a verdict came from, which is the trust story. Do: list all four sources on Blocking & screening as "What decides" with their state (U-11), and keep each control where it is. |
| **Lock now ↔ Lock private contacts ↔ Hide private contacts ↔ duress** | **Merge the two lock buttons** (U-08); keep Hide and duress apart. | Locking is one intent at the moment of use. Hide is a standing posture (a setting and a tile), and duress is a security mode. |
| **Labels header icon ↔ Labels chip** | Remove the icon. | Same destination. |
| **Favourites / Frequent / Circle / Labels** | **Keep apart.** | Round 2's reasoning stands. |
| **Tools ↔ Settings** | **Keep apart.** | Tools is the reason U-01 is fixable cheaply. |

---

## 10. Prioritised UX plan

### Quick wins (S; one release, about 1 week)
1. **Tools catch-up** (U-01):
   - add rows for Situations (featured, replacing Drive profile's featured slot), Search everything, Case files (after #9), Archived, Family shield and Chapters, with `since` tags;
   - What's new names the release's top rows;
   - the catalog test lists features.
2. **Situation tile offer** the first time a Situation is turned on (`requestAddTileService`, no permission).
3. **Label page "Add people"** and the corrected empty state (U-05).
4. **Remove `memory_lock_screen`** and migrate it into Caller on the lock screen (U-06). This frees the slot for the network-name toggle.
5. **Recents:**
   - Delete in the selection bar;
   - Remind me at the sheet's top level;
   - "Why it rang…" only for incoming calls, with the allow and report actions under "Allow, report…" (U-04).
6. **Contacts header:** drop Labels; one lock button (U-08).
7. **A–Z rail** stops swallowing the Call button's taps (U-09).
8. **Icon fixes:** Report, Chapter, encrypted QR, Lock private, Add to contact, Name in their language (U-17).
9. **Demote rare items:** Chapter row into a fold after members; Keep a case file into More… for people (U-10).
10. **Family shield** row on Blocking & screening (U-11).
11. **Small fixes:**
    - Settings summaries wrap at large fonts;
    - private swipe-delete gets Undo;
    - Circle settings opens the right group;
    - `csv_bom` becomes an export option;
    - `sim_labels` is removed.

### Structural moves (M; one release)
1. **Post-call card hierarchy:** Save (split), Remind me and Block, then More (U-03).
2. **First run:** a "Moving from another phone with Parley?" restore path, offered before Basics (U-21).
3. **Recall from Recents:** search fallback and chip (U-07).
4. **Settings tree** (§3.3):
   - Keypad and Messaging dissolve into Calls;
   - Situations page trimmed;
   - the Blocking & spam row opens the screen;
   - one "Recents view" row;
   - the People card choice merged.
   Keys are unchanged; SETTINGS.md is updated.
5. **Settings budget reclassification** (owner decision, §3.1): links become `link = true`; preferences ≤ 100.
6. **Case files index** in Tools (U-12).
7. **Archive at the moment of Delete;** an archived contact's read-only page (U-13).
8. **Label page order:** members first, settings folded.
9. **`ParleyTag`** in core/ui, used by the network, usual, variant, archived and work markers (U-25).
10. **Incoming caller header:** cap of 3 secondary lines while ringing.

### Redesigns (L; plan, prototype and owner sign-off)
1. **One "who may ring" model** (U-02, §9):
   - Quiet nights becomes the Night Situation;
   - Off hours is the engine shown on Blocking;
   - a shared "Set by <Situation>" banner on every page a Situation touches;
   - Drive profile only inside Driving.
2. **"Next time with …"** (U-22, §9): one card for To call, To talk about and Promises on the contact page and the caller header, with the storage unchanged.
3. **"Kept as" control** for contact variants (§9), replacing the Privacy… menu group.
4. **Situations on the home screen:** a reachable chip row above the nav bar (Driving · Meeting · Night · …) that people can opt into from the first Situation they use. It reuses the existing "Night is on" chip slot, keeps to the ≤ 7 rule and adds no setting (the opt-in is the tile choice).
