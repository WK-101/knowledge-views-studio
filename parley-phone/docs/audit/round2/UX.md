# Track D: UI/UX audit of Parley 5.3.0 (HEAD e085d27)

Read-only audit, based on the code and the docs (README "Where things are", GLOSSARY, WRITING, SETTINGS, ACCESSIBILITY, CALL_SCREEN_DESIGN, CONTACT_PAGE_DESIGN, EDITOR_DESIGN). Paths are relative to `parley-phone/`. Severity: **H** = hurts many users or breaks a stated rule; **M** = real friction or inconsistency; **L** = polish. Effort: **S** < 1 day, **M** 1–3 days, **L** > 3 days.

## 0. Inventory (measured from code)

| Thing | Count | Source |
|---|---|---|
| Settings entries in `SettingsCatalog` | 164 (2 of them links). Calls 33, Contacts 25, Blocking 18, Privacy 16, Recents & history 15, Backup 13, Layout 12, Appearance 10, Keypad 5, Notifications 5, Call time 4, About 4, Messaging 4 | `core/common/.../SettingsCatalog.kt` |
| …on category pages / on screens of their own | 95 / 69 (Reminders 10, Answering 8, During 8, Blocking 8, Simple mode 5, …) | same |
| Settings root rows | Tools + 13 categories (Reminders is **not** at the root) | `ui/settings/SettingsScreen.kt:138-144` |
| Tools hub rows | 46 rows in 7 jobs; 22 "featured" rows shown up front | `core/common/.../ux/Capabilities.kt` |
| Compose destinations | about 63 `composable` routes in `app/ui` (plus the call screen) | grep |
| Sheets / dialogs / dropdown menus | 28 `ParleySheet`/`ModalBottomSheet`, 81 `ParleyDialog`/`AlertDialog`, 36 `DropdownMenu` | grep |
| Snackbar or toast messages | about 181 `toast(` calls; in-context Undo in only ~8 flows | grep `offerUndo`, `UiEvent.Undo*` |
| One-time tips (coach marks) | 25 ids | `core/common/.../ux/Tips.kt` |
| Quick Settings tiles | 4 (Private, Message a number, Scan QR, Expecting a call); none is offered from inside the app | `AndroidManifest.xml:401-453`; no `requestAddTileService` |

**Overall.** Parley's IA is unusually well documented, and much of it is principled: there is one Tools hub, one Reminders page, one History & undo, a single "Message or call on…" sheet, a ≤ 7-item rule for the tab ⋮ menus, a glossary that the strings largely follow, and solid call-screen accessibility. The weak points:

1. Progressive disclosure for a *basic* user stops at the home tabs. There is no basic or advanced split and no setup step that picks defaults. Settings has 13 categories, and Tools shows 22 rows before "more".
2. Settings has become a second feature launcher. About 30 of the 95 page rows open tools or screens rather than set a preference, so most features have 3–7 entry points while some moment-of-use actions have none.
3. The ≤ 7-item and undo rules hold in the tab menus but not on the contact page, in the Recents long-press sheet or in multi-select.
4. Some settings names collide ("Keep full call history" / "Keep call history"), and two concepts have two names each (discreet mode / Hide private contacts; Delete after… / Delete automatically…).

---

## 1. Findings

### A. Placement (moment of use)

**D1 [H]. "Save contact" is missing at the moment of need: the post-call card for an unknown number.** The card offers Block, **Save privately** (a *private temporary* contact for 7 days), Message or call on…, Report, Ask their name, Was it a scam? and Call a saved number. It has no plain "Create contact" or "Add to a contact". Evidence: `telecom/.../ui/PostCallCard.kt` strings `postcall_save_privately`, `postcall_save_title` ("Save privately for 7 days"); there is no `home_create_contact` or `recents_add_to_contact` on the card. The most common thing to do after a first call with a new number is to save it normally. The privacy-first default is good, but it should not be the only choice.
→ Make **Save** the primary action, with a split or menu: *Create contact* · *Add to a contact* · *Save privately for 7 days*. Keep Block next to it. Move Report, Ask their name and Was it a scam? under "More". Effort S–M.

**D2 [H]. To call items can't be created by hand.** "Remind me" exists only on a missed-call notification, the incoming screen's ⋮ and the post-call card. Evidence: `ui/calls/ToCallScreen.kt` has no add action, and its empty state (`to_call_empty_body`) says "Choose Remind me on a missed call…". Neither the Recents long-press sheet (`ui/home/RecentsTab.kt:515-533`) nor the contact page ⋮ (`ContactDetailScreen.kt:390-441`) offers it. The moment of use is often "I see Ana in Recents, I'll call her tonight".
→ Add **Remind me to call…** (with the time chips from `RemindTimes.kt`) to the Recents row sheet, the contact page ⋮ (or its Call tile's long-press) and the number history. Effort S.

**D3 [M]. Rare tools hold prime header slots, and frequent ones are hidden.** The Contacts header has Search, **Scan QR**, **Labels**, **Lock now** and ⋮ (up to 5 icons; `ui/home/HomeScreen.kt:277-289`). The Recents header's only extra action is **Call insights** (`HomeScreen.kt:279`), a statistics page most people open rarely. Meanwhile **Blocking & screening**, which people want right after a spam call, is not reachable from Recents except through Tools or Settings (the Blocked chip only filters).
→ Contacts: keep Search and Lock now (when on). Move Scan QR into the FAB as an *Add contact* speed-dial (New contact · Scan QR · Paste details · Add several numbers). Labels are already chips above the list (`ContactsFilterChips`), so move "Manage labels" to the end of that chip row. Recents: replace Call insights with nothing or put it in ⋮, and add **Blocking & screening** to Recents ⋮. Effort S.

**D4 [M]. Expecting a call has the wrong primary home.** Its moment of use is "waiting for a delivery or callback": from the keypad after dialling a business, from a note, or from the shade. It lives as a switch in Tools and Settings › Blocking & spam, as a chip on the Blocking screen, and as a QS tile that the app never offers (no `requestAddTileService` anywhere). It is only useful when screening is on.
→ Offer "Expecting a call?" on the keypad after an outgoing call to an unsaved number (one tap, 1 h / 3 h / today), and show a one-time "Add the Quick Settings tile" offer when the user first turns on "Only people I know ring". Effort S.

**D5 [M]. Reminders, a whole Settings page with 10 settings, is not in the Settings root.** It is reached only through link rows on Calls, Contacts, Recents & history, Backup & sync, and through Tools. Evidence: `SettingsScreen.kt:138-144` lists no Reminders, while `SettingsPages.kt` calls `remindersLinkRow` four times.
→ Make **Reminders** a root category, next to Notifications. Remove three of the four link rows and keep the one on Calls. Effort S.

**D6 [M]. The Call time category is a single link.** The Call time page holds `call_time` (a link) and `sims`, a duplicate of Calls › SIMs & carrier (`SettingsPages.kt:320-326`). `call_time` is also on Calls › Situations, and both screens are in Tools.
→ Remove the Call time category and keep both rows under Calls (see target IA). Effort S.

**D7 [M]. Settings doubles as a feature launcher.** Contacts has Labels, Temporary contacts, Add several numbers, Find & merge duplicates, Contact health check, Import (×1), Export (×3), Birthdays & dates and Import from SIM. Blocking has Spam lists, Rule templates, Test a call and Import & share rules. Recents & history has Call insights, Clear call history, Deleted calls and Import call history. Messaging has Messaged numbers and My card. Backup has five screen links. About 30 of the 95 page rows open tools rather than change a preference. The result is 3–7 entry points per tool (table D-map) and long Settings pages, while Tools already exists to launch them.
→ Rule: **Settings holds preferences, and Tools and the tabs hold actions.** Keep a feature's *settings* in Settings, with a "Open …" link only where the feature's settings live inside the feature's screen. Remove the pure launchers (Duplicates, Health, Bulk add, Birthdays, Insights, Test a call, Messaged numbers…) from Settings pages; search still finds them through `SettingPlace.TOOLS`. Effort M. **Caveat:** keep Import/Export in Settings › Contacts, because users look for it there in every contacts app.

**D8 [M]. Tools is missing features it claims to list ("everything Parley does").** There are no rows for Drive profile, Shared labels ("family phonebook"), Voicemail, Speed dial, RTT, Make a ringtone (caller tune), widgets, Quick Settings tiles, Export notes as Markdown or Call with a reason. Evidence: `Capabilities.kt` rows. Meanwhile two rows go to the same place: "Send my details" and "A card that stays current" both open `MY_CARD` (`Capabilities.kt:170,174`), and "Lock Parley now" and "App lock" both use key `app_lock` (`:150,:160`).
→ Add the missing rows (mostly in "Calls that work better" and "Know who's calling"). Fold "A card that stays current" into the My card row's summary. Make "App lock" a single row whose trailing action is *Lock now* when it is on. Effort S.

**D9 [L]. Export notes as Markdown opens the Sync screen at its top.** The row lands on Sync while the feature is a section further down. Evidence: `SettingsPages.kt:705` → `Routes.Sync`; the section is at `ui/sync/FolderSyncScreen.kt:142-143`.
→ Scroll to the section, or give it its own route. Effort S.

**D10 [L]. "Reset tips" is under Appearance › Tips** (`SettingsPages.kt:215`), and nothing in About offers Help, the tour or What's new again. About's summary promises a "licence" row that doesn't exist (`SettingsCatalog` ABOUT summary "Version, licence, diagnostics"; `AboutPage` at `SettingsPages.kt:755-775`).
→ Add **About › Help & tips**: Show tips again, What's new in 5.3, Open-source licences. Effort S.

### B. Settings and options

**D11 [H]. A basic user gets no basic/advanced split, and the first run sets no defaults.** Only three `AdvancedGroup`s exist: Contacts (`SettingsPages.kt:483`), Recents (`:578`) and Privacy (`:679`). SETTINGS.md §3.5 says the connect buzz, the proximity sensor and the power button are Advanced, but `CallFeedbackGroup` shows them openly (`CallExtrasSettings.kt:100-112`), so the doc has drifted. Onboarding is Welcome → Default dialer → Permissions → Coming from another phone (`OnboardingScreen.kt:110-116`). It never asks the two questions that shape day one ("Who can ring you?", a preset that already exists as `ScreeningPreset`; and "Who is this phone for?", standard or Simple mode). What's new never shows on a fresh install (`Tips.kt:112`), so a new user's only way to discover the 46 tools is ⋮ › Tools.
→ (a) Add one optional onboarding step, **"Set up the basics"**: Who can ring (the 4 presets), Simple mode yes/no, and the layout (Classic / Calls+Contacts). (b) In Settings, show each page's basic rows and fold the rest under the existing `AdvancedGroup` pattern consistently (proposed split in §3). Don't add a global "advanced mode" switch, because it hides things from search and support. Effort M.

**D12 [H]. "Keep full call history" and "Keep call history" do opposite things.** `archive` keeps Parley's copy forever. `retention` *deletes* calls from the system log after a while. A third row, "Numbers kept forever", exists only while the archive is on (`SettingsPages.kt:544-552`; catalog `:319-325`).
→ Rename to **"Parley's call archive"** (switch, with count) and **"Delete old calls from Android's call log"** (Never / 30 / 90 / 365 days). Better still, merge them into one choice, *Call history: Android's log only · Keep everything in Parley (encrypted) · Keep everything, and tidy Android's log after N days*. Effort S (rename) / M (merge).

**D13 [M]. Some switches should be choices.**
- `learn_from_calls` + `silence_sales_lines` (the second is dependent; `SettingsPages.kt:341-350`) → **Sales lines: Off · Tag quietly · Tag and silence**.
- `call_haptics` ("Vibrate on call events: connects, ends, swapped…") + `connect_haptic` ("Vibrate when a call connects") overlap on "connects" (`CallsSettings.kt:26-45`) → **Vibrate during calls: Off · When answered · On every change**.
- `hide_vault` + the Quick Settings Private tile + "Lock now": discreet mode, app lock and lock now are three privacy postures. → A **Privacy level** chooser on Privacy & security (Normal · Locked · Discreet) with the details under it.
- `recents_layout` (Settings and Recents ⋮), `recents_style`, `calls_layout` and `recent_tap` all shape Recents, on two pages (Layout & gestures and Recents & history). → One **"Recents view"** sheet reachable from Recents ⋮: layout · style · tap action · docked keypad.
Effort M.

**D14 [M]. Duplicates and near-duplicates in Settings.**
- `sims` appears on Calls › SIMs & carrier, Call time and Tools; `call_time` on Call time, Situations and Tools.
- `repeat_callers` is on Settings › Blocking & spam *and* inside Blocking & screening › Allow (`BlockingScreen.kt:~305-315`).
- `private_names` and `private_directory` are two rows that open the same screen (`SettingsPages.kt:677,680`).
- `journal`, `time_machine` and `history_details` are three rows into History & undo.
- `memory_prompt` is on During calls and on Reminders.
→ One row per destination per page. For the History & undo trio, keep one row "History & undo" with the tab named in its summary. Effort S.

**D15 [M]. Some global settings should be per contact or per label (and they almost are).**
- **Start calls on speaker**: global (Never / Always / Unknown numbers). Grandparents and hands-free relatives make "for this person" the real need. → Add it to *Settings for this contact* and the label page, as auto-answer and vibration already are.
- **Quick reply messages**: global. → Optionally per label (Work vs Family), or at least favourite replies per contact.
- **Prefer nicknames**: global. → A per-contact "Show as nickname" fits better, because nicknames are personal.
- The reverse: **Caller on the lock screen** is correctly global, with private contacts only able to hide more. Keep it.
Effort M each.

**D16 [M]. Weak or jargon names** (WRITING.md: "plain, short").
- "Situations" → **Special calls**, or split as in §3.
- "Anything to remember? after calls" (a question mark mid-title) → **Note after calls**.
- "Peek before calling" → **Show notes before calling**.
- "Silence numbers that look like sales lines (your calls)" → see D13.
- "USSD replies" → **Carrier codes (*#…) replies**.
- "Excel-friendly CSV" → move it into the export sheet as a checkbox.
- "Log interaction" (`circle_log_interaction`) → **Log a chat or visit**.
- "Delete after…" / "Change auto-delete" (`detail_delete_after`, `detail_change_expiry`) vs "Delete automatically…" (`contact_make_temporary`, and the README and `temp_empty_text`, which point users to a "Delete automatically" item on the contact page that doesn't exist under that name).
- "Move to private" (`sel_move_private`) vs the glossary's **Make private**.
- "Recently deleted calls" (`hist_recently_deleted`, used in `ui/history/DeletedCalls.kt:50`) is a GLOSSARY "Words to avoid" item.
- "Discreet mode" (in `set_hide_vault_summary` and 4+ strings) is not in the glossary, yet it names the same thing as "Hide private contacts".
→ Fix the strings, add *Discreet mode* to the glossary or drop it. Effort S.

**D17 [L]. Simple mode lives under Layout & gestures.** It is a persona switch, not a layout tweak, and it is usually set up *for someone else*.
→ Show it on the Settings root as a card ("Setting up this phone for someone? Simple mode"), in onboarding (D11) and in Tools. Effort S.

**D18 [L]. My card sits under Messaging** (`SettingsPages.kt:595`). Users look for it on top of Contacts (where it already is) and in Contacts settings. → Move it to Contacts. Effort S.

### C. UX quality and consistency

**D19 [H]. Three ⋮/sheet surfaces break the ≤ 7 rule that the README and glossary state.**
- **Contact page ⋮: up to 14 items.** Log interaction, Share file, Show QR, Share privately (encrypted QR), Version history, Add to home screen, Copy to SIM, Set ringtone, Block numbers, Allow prefix…, Separate, Make private/visible, Delete after…, Delete (`ContactDetailScreen.kt:388-441`). At least two repeat the *Settings for this contact* section: ringtone (`:736-745`) and Make private (`:783-787`).
- **Multi-select ⋮: up to 10.** Add to label, Message all, Introduce, Merge, Copy as text, Export .vcf, Delete automatically…, Move to private, Make visible, Delete (`SelectionBar.kt:146-211`).
- **Recents long-press sheet: up to ~16 rows.** Call, Send message, Message or call on…, Edit before call, Copy, Create contact, Add to contact, Block, Select, Why it rang/blocked, Test, reputation, Always allow, Allow…, Report, Search web, Delete (`RecentsTab.kt:515-533` plus `RecentBlockingActions`).
→ Contact ⋮ (target 6): **Share…** (a sheet: file · QR · encrypted QR · copy as text), Version history, Add to home screen, Block/Unblock, Merge/Separate, Delete. Ringtone, privacy, auto-delete, Copy to SIM and the prefix allowance stay in *Settings for this contact*. Log interaction moves to the Timeline section's "+". Selection ⋮ (target 7): group Share/Copy/Export into one **Share…**, and put Make private / Make visible / Delete automatically under **Privacy…**. Recents sheet: put a header row of 4 icon buttons (Call, Message, Message or call on…, Copy), then ≤ 6 rows, with the screening items under one "Why it rang · Allow · Report…" row. Effort M.

**D20 [H]. Block behaves differently depending on where you tap it, and it is state-blind.**
- Recents sheet "Block number" and contact ⋮ "Block numbers" call `vm.blockNumber` → Android's **system** blocked list (`AppViewModel.kt:444-449` → `BlockRepository.kt:206-212`). That needs the default-dialer role, and otherwise toasts "Couldn't block".
- The post-call Block opens Parley's **rule editor**.
- Blocking suggestions add a **Parley rule** (`BlockingActions.blockNumberRule`).
- None of the three asks first or offers Undo, and the contact page blocks *every* number of a saved contact with one toast per number.
- No surface shows "Unblock" when the number is already blocked (no `unblock`/`isBlocked` in `ContactDetailScreen.kt` or `RecentsTab.kt`).

Users end up with numbers split between "Your rules" and "Blocked numbers (system list)".
→ One `BlockFlow`: a small sheet (Block calls · Block calls and report · Advanced: rule editor) writes a Parley exact rule (mirrored to the system list when Parley is the default app), shows "Blocked · Undo", and flips to **Unblock** wherever the number appears. Effort M.

**D21 [M]. Long-press means three different things.**
- Contacts row: select (`ContactsTab.kt:190`).
- Recents row: action sheet, or select while selecting (`RecentsTab.kt:264`).
- Favourites tile: open contact, while a tap *calls* (`FavoritesTab.kt:221-269`).
- Label member: row menu (`LabelScreens.kt:520`).
- Combined surfaces: tap calls, long-press opens (`CombinedSurfaces.kt:268`).
- Contact tiles: Call → call with a reason, Message/Video → Message or call on….

The labels exist for TalkBack, but sighted users must learn each list.
→ Pick one rule for lists: **long-press = select** (Contacts, Recents, label members), with a trailing ⋮ or a swipe for the row's actions, and keep long-press-for-alternatives only on *buttons* (Call, Message, Speaker), where it is the platform convention. Effort M.

**D22 [M]. Undo is uneven.** Swipe-delete in Recents has Undo (`AppViewModel.kt:424-434`), but **"Delete from history" in the long-press sheet deletes silently** (`RecentsTab.kt:531-533` → `RecentsViewModel.kt:111-116`: no message and no Undo). Block and Allow have no Undo (D20). `offerUndo` is used once in the whole app (`ContactEditScreen.kt:248`).
→ Route every destructive or list-changing action through `deleteCallsWithUndo` / `offerUndo`, as WRITING.md asks ("Blocked and declined. Undo"). Effort S.

**D23 [M]. Recents filter chips are icon-only until selected.** There are 8 chips (All, Missed, Incoming, Outgoing, Unknown, Contacts, Blocked, Voicemail) plus saved filters, shown as compact chips with the label only when on or when space allows (`RecentsFilterRow.kt:150-175`). With "Rich" style tints, Recents needs a legend menu item ("What do the colours mean?", `RecentsLegend.kt:334`).
→ Show labelled chips for the top four (Missed, Unknown, Voicemail, Blocked) and fold the rest into a "Filter" chip with a sheet. Make the legend a one-time inline tip instead of a menu item. Effort S–M.

**D24 [M]. Tools rows have no icons and no state.** `HubRow` is a plain `LinkRow(title, summary)` (`CapabilitiesScreen.kt:~100-108`). Among 22 visible rows, nothing says which are already set up (App lock on? 3 spam lists? Circle empty?).
→ Add the category icon and a trailing status ("On", "3 lists", "Not set up"). Then order each job's featured rows as *not set up* first for a new user and *most used* first after a month. Effort S–M.

**D25 [M]. In-call More sheet order.** Rare safety rows (Is this a scam?, Check it's really them, Add my helper, Says they're family) and Hold mode come **before** Add a note, Open contact and Copy number, the actions most users want mid-call (`telecom/.../ui/CallTimeUi.kt:224-292`). For an unknown caller the sheet can reach 12 rows plus the Call time chips.
→ Order it as overflowing grid controls → Add a note · Open contact · Copy number → a headed **"Not sure who it is?"** group (scam, verify, safe word, helper) → Hold mode / RTT → Call time. Effort S.

**D26 [M]. Error messages without a reason or a next step.** 58 "Couldn't…" strings. Many end flatly: "Couldn't install the list", "Couldn't import", "Couldn't read the file", "Couldn't check" (`strings_blocking.xml:338,489-491,527,651,748,763`). WRITING asks to "Say what happens"; the vault/PIN ones do it well ("Nothing has changed", "Try again").
→ Add the reason class (file format / permission / not the default phone app) and one action. Effort S–M.

**D27 [L]. Quick reply messages are edited inside an AlertDialog** that holds a growing list of text fields plus the name reply (`SettingsScreen.kt:355-385`). With a keyboard on a small phone or at a large font this becomes cramped. → A full page with add, remove and reorder. Effort S.

**D28 [L]. Bug: Recents sheet "Select" uses the Block icon** (`RecentsTab.kt:529`, `Icons.Rounded.Block`), next to the real Block row. → `Icons.Rounded.CheckCircle` or `SelectAll`. Effort S.

**D29 [L]. Large fonts: Settings category and Tools summaries are `maxLines = 1`** (`SettingsScreen.kt` category `ListItem`). At 200 % the summary that tells you what's inside is mostly an ellipsis. → Allow 2 lines above a font scale of 1.3. Effort S.

**D30 [L]. One-handed reach.** Every home action except the tabs and the Contacts FAB sits in the top bar (search, ⋮ → Tools/Settings), and so do the contact page's Star, Edit and ⋮. → Material 3 Expressive allows a bottom search bar or pull-down-to-search on Contacts and Recents. Also consider a docked toolbar on the contact page (Call · Message · Edit · More) once the header has scrolled away; the pinned action strip partly does this. Effort M.

**D31 [L]. Doc drift that misleads maintainers.** SETTINGS.md lists Advanced groups that no longer exist (D11), omits `delete_all_data` (Privacy › Advanced, `SettingsPages.kt:679`), and calls the Call time page a category with real content. → Update with the IA change. Effort S.

### D. Accessibility (beyond what docs/ACCESSIBILITY.md already covers well)

**D32 [M]. Favourites tile: a tap places a call.** That is the platform convention, and the click label "Call" is set (`FavoritesTab.kt:265-269`). But for Switch Access or Voice Access users the *only* way to reach the contact is the long-press custom action. → Add a small visible "info" affordance, or an "Open" item in the tile's accessibility actions list (not only the long-press). Effort S.

**D33 [L]. Colour-coded Recents.** "Rich" style relies on tints plus shapes, with the not-returned tint at 8 % alpha (`RecentsTab.kt:328`, `RecentsLegend.kt:302`). The shapes and icons carry meaning, so this passes the "not colour alone" rule, but the 8 % tint is invisible to many people. → Raise it to ~14 %, as CallGlance does (`CallGlance.kt:91`), or add the word "Not returned". Effort S.

### E. Consolidation

**D34 [M]. Good merges.**
1. **Call time category → Calls** (D6).
2. **Keypad (5 rows) → Calls › Keypad group**: tones, letters, speed dial and USSD are all dialling.
3. **Messaging (4 rows) → split**: My card to Contacts, Quick replies to Calls › Answering (it's used while declining), Messaged numbers to Privacy › Your data, where the privacy dashboard already lists it.
4. **Recents view options** into one sheet (D13).
5. **History & undo's three Settings rows** into one (D14).
6. **Tools "Send my details" + "A card that stays current"**, and **"App lock" + "Lock now"** (D8).
7. **One Block flow** (D20).

**D35. Merges that would hurt (don't do these).**
- **Favourites, Frequent, Circle and Labels**: the glossary's "Which one?" separation is right and well explained. Merging Circle into Favourites would star people silently, the exact confusion the DND confirmation avoids.
- **Tools into Settings** (or the reverse): Tools is task-oriented and Settings is preference-oriented. Keep both and stop *duplicating* (D7).
- **Blocking & screening into the Settings page**: the screen's presets, then basics, then the "Advanced" collapsibles, is the best progressive disclosure in the app. Keep it a screen of its own.
- **Answering and During calls pages**: they are now right-sized (8 + 8). Merging them would recreate the 31-row Calls page that 5.1 fixed.
- **Private and Temporary**: they are variants with separate chips and lifetimes, and merging them would blur "hidden" with "expires".

---

## 2. Entry points per feature (D-map)

Counts include tabs, headers, ⋮ menus, Tools rows, Settings rows (not search), screens, notifications, launcher shortcuts and QS tiles. Bold marks the recommended **primary** home.

| Feature | Entry points today | n | Recommendation |
|---|---|---|---|
| Blocking & screening | Tools (Choose who can ring), Settings › Blocking & spam, post-call Block → rule editor, Recents sheet (Why…/Test), Recents Blocked chip | 5 | **Tools › Stop spam** + Recents ⋮ (add) + onboarding preset; Settings row stays (it has settings) |
| Expecting a call | Tools switch, Settings › Blocking switch, Blocking screen chip, QS tile (never offered), note/QR hints | 5 | Keep; add a keypad after-call prompt and a tile offer (D4); drop the Settings › Blocking duplicate |
| Scan QR | Contacts header, Tools, My card › Scan theirs, launcher shortcut, QS tile, Settings search | 6 | **Contacts FAB speed-dial**, Tools, shortcut and tile; drop the header icon |
| Temporary contacts | Contacts chip, Tools, Settings › Contacts, keypad Save temporary, contact ⋮, selection ⋮, post-call Save privately | 7 | Fine as it is (a variant); fix the naming (D16) |
| History & undo | Tools, Settings › Backup (×2 rows), Settings › Recents (Deleted calls), messages naming it | 4 | **Tools** + one Settings row |
| SIMs & plan minutes | Calls › SIMs & carrier, Call time page, Tools | 3 | **Calls › SIMs & carrier** + Tools |
| Call time limits | Call time page, Calls › Situations, Tools, contact page settings, in-call More | 5 | **Calls** (one row) + contact/label + in-call |
| Reminders page | Calls, Contacts, Recents & history and Backup link rows, Tools; *not the Settings root* | 5 | **Settings root** + Tools |
| To call (list) | Recents strip, Tools, Reminders link, notifications | 4 | Fine; add *creation* points (D2) |
| Call insights | Recents header icon, Tools (more), Settings › Recents & history, contact page section | 4 | **Recents ⋮** + Tools + contact section; drop the header icon and the Settings row |
| Find & merge duplicates | Contacts ⋮, Tools (more), Settings › Contacts, multi-select Merge, health check | 5 | **Contacts ⋮** + Tools + health check |
| Contact health check | Tools, Settings › Contacts | 2 | Tools + a Contacts ⋮ "Tidy contacts" that holds both duplicates and health |
| Add several numbers | Contacts ⋮, Tools (more), Settings › Contacts, shared-text "Save all" | 4 | **Contacts FAB speed-dial** + share target |
| Labels (manage) | Contacts header, Tools, Settings › Contacts, chips | 4 | **Chip row "Manage"** + Tools |
| Simple mode | Settings › Layout & gestures, Tools | 2 | + onboarding + Settings root card (D17) |
| Drive profile | Settings › Calls › Situations | 1 | + Tools (D8) |
| Shared labels | Sync screen, label ⋮ Share | 2 | + Tools (D8) |
| Helpers | Calls › Situations, simple mode setup, Tools (more), in-call More | 4 | Fine |
| Family safe word | Privacy row, label page, Tools (more), in-call More | 4 | Move the Settings row to Calls › "Not sure who it is?" (it is a call-safety feature, not app privacy) |
| My card | Contacts top row, Settings › Messaging, Tools ×2 | 4 | Contacts row + Tools ×1; Settings row → Contacts |
| Remind me (create a To call item) | missed-call notification, incoming ⋮, post-call | 3 | + Recents sheet, contact page, number history (D2) |
| Save an unknown number normally | Recents sheet (Create / Add to contact), keypad | 2 | + **post-call card** (D1) |
| Lock now | Contacts header, Tools (+ the App lock row) | 2–3 | Contacts header + one Tools row |

---

## 3. Proposed target information architecture

### Tabs (unchanged by default)
Favourites · Recents · Contacts · Keypad (Circle hidden until used). Onboarding's "Set up the basics" offers **Classic (4 tabs)**, **Compact (Calls with keypad + Contacts)** and **Simple mode**. All three already exist as settings; this only surfaces them.

### Headers and menus (≤ 7, with the tab's own items first, then Tools and Settings)
| Tab | Header icons | ⋮ |
|---|---|---|
| Favourites | Search | Reorder · Tools · Settings |
| Recents | Search | **Blocking & screening** · Call insights · Recents view… (layout, style, tap, keypad dock) · Export… · Clear call history · Tools · Settings |
| Contacts | Search · Lock now (when on) | Select… · Tidy contacts (duplicates + health check) · Import & export · Tools · Settings |
| Contacts FAB | Add contact ▸ speed-dial: New contact · Paste details · Scan QR · Add several numbers |
| Keypad | Search · Speed dial | Tools · Settings |
| Contact page ⋮ | Star · Edit | Share… · Remind me to call · Version history · Add to home screen · Block/Unblock · Separate (when linked) · Delete |
| Recents row (long-press) | (select) | trailing ⋮ / sheet: [Call · Message · Apps · Copy] then Remind me · Create/Add to contact · Block/Unblock · Why it rang… · Delete (Undo) |

### Tools (task hub). Keep the 7 jobs and add icons and status. Featured rows per job ≤ 3 for new users:
- **Stop spam**: Choose who can ring · Expecting a call (switch) · Spam lists | more: Test a call, Rules for your country, Bring your block list, Sales lines
- **Never lose a contact**: Encrypted backups · History & undo · Coming from another phone? | more: Snapshots, Sync, Import & export, Health check, Duplicates, Export notes as Markdown
- **Stay in touch**: To call · Circle · Birthdays & dates | more: All your reminders, Anything to remember?, Who's in…, Call insights, Shared labels
- **Know who's calling**: Labels with their own ringtone · Scan QR | more: Vibration of their own, Unknown-caller ringtone, Make a ringtone, Family safe word
- **Keep it private**: App lock (with Lock now) · Private contacts · Temporary contacts | more: Privacy dashboard, Who can see your contacts, Messaged numbers
- **Message without saving**: Message a number · Messaged numbers | more: Add several numbers, Paste details, My card, Quick replies
- **Calls that work better**: Answer automatically · Simple mode · Drive profile | more: Helpers, Talk-time reminders, SIMs & plan minutes, Pocket calls, Missed-call re-alert, Voicemail, Speed dial, RTT, Widgets & tiles

### Settings tree (B = basic, shown; A = under "Advanced" on that page)
1. **Simple mode card** (only while it's off and Parley isn't set up for someone else; dismissible)
2. **Tools** (link, as today)
3. **Appearance**: B Theme, Wallpaper colours, Sort by, Show names as · A Pure black, List density, Avatars, Second line, Prefer nicknames
4. **Layout & gestures**: B Navigation bar, Open on · A Calls layout, Favourites in Contacts, Tapping a call in Recents, Swipe actions
5. **Calls**: B Default phone app, Confirm before calling, Ask before pocket calls · pages: **Answering** (B answer gesture, ringtone for unknown callers, caller photo, quick replies · A background, flip to silence, auto-answer, vibration for callers, RTT) · **During calls** (B speaker, screen at ear · A vibrate during calls [merged], power button, note after calls, show notes before calling) · **Keypad & dialling** (B tones, vibration · A letters, speed dial, carrier codes, assisted dialling, phone menus) · **SIMs & carrier** (SIMs & plan minutes, calling accounts, forwarding/waiting) · **Call time** (reminders and limits) · **Not sure who it is?** (helpers, family safe word, drive profile)
6. **Blocking & spam**: B Blocking & screening ↗, Expecting a call from your notes · A Sales lines (choice), Spam lists, Rule templates, Import & share rules
7. **Contacts**: B Save new contacts to, My card, Call and message buttons in the list, Import, Export · A Add relations to both, Contact page sections, Import from SIM, Export one account, Log messages you start
8. **Recents & history**: B Call history (merged archive/retention choice), Remember the Recents filter · A Numbers kept forever, Show SIM, Import call history, CSV options
9. **Reminders** ↗ (moved to the root; unchanged inside)
10. **Privacy & security**: B App lock (+ Lock again after, Unlock with), Caller on the lock screen, Hide private contacts (discreet mode) · A Hide screen content, Private call history, Let apps show private names (one row), Who can see your contacts, Messaged numbers + Forget after, Privacy dashboard, App permissions, Delete all Parley data
11. **Backup & sync**: B Backup & restore ↗, History & undo ↗ · A Sync between your phones ↗ (with Shared labels), Export notes as Markdown
12. **Notifications & device** (unchanged)
13. **About**: Version, Help & tips (Show tips again, What's new), Licences, Diagnostics, Keep crash reports

The root goes from 14 rows to 13 rows plus a card: Keypad, Call time and Messaging dissolve, and Reminders becomes a root row. Visible rows per page for a basic user drop by roughly 40 %. No stored key changes; only `SettingPlace` and the page code move, and search keeps working through `SettingsCatalog`.

---

## 4. Ranked top 15

| # | Finding | Sev | Effort |
|---|---|---|---|
| 1 | D20 One Block flow (system list vs rules vs editor), with Undo and an Unblock state | H | M |
| 2 | D1 Post-call card: add a plain Save / Add to contact | H | S–M |
| 3 | D19 Contact ⋮ (14), selection ⋮ (10) and Recents sheet (~16): back to ≤ 7 | H | M |
| 4 | D11 Onboarding "Set up the basics" (screening preset, Simple mode, layout) + a consistent basic/Advanced split | H | M |
| 5 | D12 Rename or merge "Keep full call history" / "Keep call history" | H | S |
| 6 | D2 Create To call items from Recents, the contact page and number history | H | S |
| 7 | D22 Undo for Recents sheet delete, block and allow; use `offerUndo` everywhere | M | S |
| 8 | D7 Settings = preferences; remove the ~30 launcher rows that duplicate Tools | M | M |
| 9 | D5 + D6 Reminders to the Settings root; dissolve the Call time category | M | S |
| 10 | D3 Header slots: Scan QR → FAB speed-dial, Insights → ⋮, Blocking into Recents ⋮ | M | S |
| 11 | D21 One long-press rule for lists (select) | M | M |
| 12 | D16 Naming fixes (Delete automatically, Make private, Recently deleted, discreet mode, Situations) | M | S |
| 13 | D13 Switches → choices (Sales lines, Vibrate during calls, Recents view sheet) | M | M |
| 14 | D8 + D24 Tools: missing rows, duplicate rows, icons and status | M | S–M |
| 15 | D25 In-call More order: note, contact and copy first, safety group headed | M | S |

---

## 5. Summary (≤ 300 words)

Parley's information architecture is more deliberate than most dialers'. It has one Tools hub, one Reminders page, one History & undo, one "Message or call on…" sheet, a glossary the strings mostly follow, ≤ 7-item tab menus and strong call-screen accessibility. The problems are about growth. At 164 settings, 46 Tools rows and about 63 screens, a basic user gets no help choosing. Onboarding never asks who may ring or whether the phone is for someone else, and What's new is skipped on fresh installs. There is no consistent basic/advanced split: only three pages fold an "Advanced" group, and the doc claims more.

Settings has also become a second launcher. About 30 of its 95 page rows open tools, so features have 3–7 entry points while some moment-of-use actions have none. You can't save an unknown caller normally from the post-call card, can't add a "To call" item from Recents or a contact, and can't reach Blocking from Recents. Reminders, a 10-setting page, is missing from the Settings root, while "Call time" is a whole category holding one link.

Consistency breaks where the rules weren't applied. The contact page ⋮ has up to 14 items, multi-select up to 10 and the Recents sheet up to ~16. Long-press means select, menu or open depending on the list. "Block" writes to Android's list in one place and to Parley's rules in another, without confirmation, Undo or an Unblock state, and deleting a call from the Recents sheet has no Undo. Some names collide ("Keep full call history" vs "Keep call history") or split one concept in two (Delete after… vs Delete automatically…, discreet mode vs Hide private contacts).

The recommended moves are mostly S–M effort: a "Set up the basics" step, a preferences-only Settings tree with Reminders at the root, one Block flow with Undo, ≤ 7-item menus via Share… and Privacy… groupings, and contextual Save and Remind me actions. Keep Favourites, Circle and Labels separate, keep the Blocking screen, and keep the Calls sub-pages.
