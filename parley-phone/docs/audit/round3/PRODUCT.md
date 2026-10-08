# Track E (round 3): Product audit of Parley 6.2.1

*8 Oct 2026, HEAD `a67fd3d6` (Merge 6.2.1). This audit is read-only. It draws on README "New in" rows 3.3–6.2.1, docs/AUDIT_2.md (§5 and §8), docs/audit/round2/PRODUCT.md, docs/COMPETITIVE_ANALYSIS_7.md, GLOSSARY.md, SETTINGS.md, DEVICE_CHECKLIST.md, TESTING.md §39–43, spot reads of the code, and a web check of the 2026 market (sources at the end). Paths are relative to `parley-phone/`.*

*Scale: about 160k lines of main Kotlin (app 71.8k, core/common 41.2k, core/data 30.3k, telecom 13.6k, core/ui 2.5k, lists-updater 0.8k) and 3,105 JVM tests (`@Test`). Instrumented tests are 76 lines; lists-updater has no tests.*

---

## 0. Summary

**Verdict.** Parley 6.2.1 is broader than any phone or contacts app on the market, and the 6.x signature work is mostly well engineered. Two examples:
- Situations snapshot and restore field by field, so a change the user makes while a Situation is on is kept (`Situations.kt:276-303`).
- "Never calls you" refuses to speak unless Parley's own call copy reaches back past the first call (`NeverCallsYou.kt:93-94`).

What separates Parley from a polished release is no longer features. It is four things:

1. **Proof on phones.** Nothing has run on a device (`DEVICE_CHECKLIST.md:3`). The checklist has no 6.x line. Android 17 shipped in June 2026, and Parley targets API 36 and has never met it.
2. **Discoverability of the 6.x work.** The Tools hub, which feeds "What's new", has no row for Situations, Search everything (Recall), Case files, the Family spam shield, Archived, Chapters, To talk about or Dead-number radar. An updated user is told about Rescue call only.
3. **Unfinished edges in 6.x:**
   - A Situation switched on by hand never ends.
   - Archive works one contact at a time and has no Undo. Its confirmation doesn't say the contact leaves the Google account, or that a large photo is shrunk.
   - Case files have no list.
   - "Never calls you" disarms itself after one inbound call, possibly the scam call itself.
4. **Release scaffolding:** help, crash follow-up, and upgrade and restore safety across versions.

**Market check (2026).** Most of what is new elsewhere needs call audio, a cloud or an account:
- iOS 26 Call Screening and Hold Assist.
- Samsung's One UI 8.5 Call Screening.
- Pixel scam detection and RCS fake-call checks.
- iOS 27 Call Context, which reads Mail.

The buildable gaps are small and concrete:
- A birthday or anniversary line on the call screen (iOS 27.2).
- Offline equivalents of Call Context and Wait Times, built on Case files.
- A "now" suggestion strip, like Truecaller's suggestions.
- Handling of VoIP rows in the call log (One UI 9).
- Readiness for the Android 17 contact picker.

**New signature features.** Eleven are proposed (§4), each offline with no new permission and no new setting. The first five are S or S–M:
1. Promise a time.
2. Callback watch.
3. Fresh-number warning.
4. Queue memory.
5. Next up.

**X9 Courtesy hours and X11 Vouched introductions.** Both need other people running Parley and exchanging signed cards, so they are worth almost nothing before a public release. Keep them out of the plan, and fold their local value into Next up and the fresh-number warning.

---

## 1. Feature inventory and maturity

Ratings:
- **Solid:** complete, tested in code, coherent.
- **Rough:** works, but has visible edges or depends on the carrier or phone maker without a device run.
- **Half-built:** missing an obvious part of its job.
- **Unused-looking:** few people will find it or need it.

Nothing is rated "proven": no device has run any of it.

### Dialer and calling
| Feature | Maturity | Note |
|---|---|---|
| T9 across 11 scripts, ranked by use; speed dial; USSD and MMI codes | Solid | |
| Call pill (multi-SIM), confirm before calling, pocket guard | Solid | |
| Calling abroad (home-format conversion, local-SIM hint) | Rough | Carrier and roaming behaviour untested |
| Call with a reason / call subject | Rough | Carrier-dependent |
| Send to another number (deflect) | Rough | Rarely supported by carriers; transfer isn't available to third-party apps |
| SIM that learns (6.1) | Solid | Suggests only, never switches |

### Call screen
| Feature | Maturity | Note |
|---|---|---|
| Incoming and ongoing screen, answer slider, call waiting, conferences, routes, PiP, CallStyle notification | Solid in code | Never run on a device |
| Caller card: note, last call, local time, Who is this?, agenda (6.2), case file (6.1), the network's caller name (6.2.1) | Solid | |
| "This number never calls you" (6.0) | Rough | Disarms after one inbound call (§2.3 C1) |
| "Is this a scam?" sheet | Rough | Static list of six signs; "Was it a scam?" records nothing (`PostCallCard.kt:78`) |
| I'm on hold | Unused-looking | Only a timer with buzzes at 15 and 30 min (`HoldMode.kt:7-12`) |
| Menu memory and shortcuts | Solid | |
| Add my helper (conference) | Rough | Needs carrier conference support |
| RTT | Rough | Carrier-dependent |
| Speaker by default, screen off at your ear, flip to silence | Rough | Sensor and OEM behaviour untested |
| Rescue call (6.2) | Rough | Inexact alarm (`RescueCalls.kt:313-316`) and never seen on a device |

### Screening and blocking
| Feature | Maturity | Note |
|---|---|---|
| Rules with an order, "why it rang", Test a call and replay, presets, repeat callers, Expecting a call, expected calls from notes | Solid | Parley's strongest area |
| Offline spam lists through the Lists companion | Solid | The companion has no tests |
| Sales lines learned from your own calls | Solid | |
| Family spam shield (6.1) | Rough | Built well, but adoption needs a shared label over Syncthing or Nextcloud on every member's phone |
| One Block flow, with Undo and Unblock | Solid | |

### Recents and history
| Feature | Maturity | Note |
|---|---|---|
| Styles (Rich, Simple, Cards), groupings, docked keypad, legend | Solid | Heavy for new users |
| To call, Remind me, follow-ups | Solid | |
| Voicemail (carrier inbox where available) | Rough | |
| Parley's call archive (5 years), History & undo | Solid | |
| Call insights, Call quality, Dead-number radar (6.1) | Rough | The radar depends on carrier disconnect causes |
| The network's caller name kept after the call (6.2.1) | Solid | New, so nothing has met real CNAP yet |
| VoIP call rows | Missing | The column is read but never used (§2.3 C4) |

### Contacts
| Feature | Maturity | Note |
|---|---|---|
| Editor, contact page, RFC 9554 fields, custom fields, all phone types, relations and relationship status | Solid | |
| Name in their language, several languages, citizenship (6.2) | Solid | |
| Labels, filters, sort, bulk edit, duplicates, health check | Solid | |
| Paste details, QR scan without the camera, My card (signed, updates itself) | Solid | |
| Private contacts, temporary contacts, Parley PIN and duress PIN | Solid | |
| Archive variant (6.2) | Half-built | One at a time, no Undo, no "spring clean" path, silent photo shrink (§2.2 H2) |
| Chapters (6.2) | Solid | Asks once at the end; nothing happens by itself |
| Snapshots, version history, sync watchdog | Solid | |
| Work-profile contacts in search | Solid | |
| Generated ringtones from a name | Unused-looking | Silent fallback on some phones |
| Introduce myself… | Unused-looking | |

### Relationships
| Feature | Maturity | Note |
|---|---|---|
| Circle (rhythms, promises, timeline), birthdays (lunar, Hebrew, Hijri), Who's in…, GoodTime | Solid | |
| To talk about (agenda, 6.2) | Solid | Stored as promise lines of the note for calls, so every open promise also shows as something to talk about (nit) |
| Case files (6.1) | Half-built | No list of case files, no status or next step (§2.2 H3) |

### Moments
| Feature | Maturity | Note |
|---|---|---|
| Situations (6.0) | Rough | Manual on has no end; Travelling has no roaming trigger (§2.2 H1) |
| Drive profile, auto-answer | Rough | Depends on Bluetooth and car mode, untested |
| Talk-time reminders and limits, Simple mode | Solid | |
| Plan minutes per SIM | Unused-looking | |

### Data, sync and privacy
| Feature | Maturity | Note |
|---|---|---|
| Encrypted backups (with family safety, drive switches, ringtones), exports including private contacts (vCard, encrypted vCard, CSV) | Solid | No old-version backup fixtures (§5) |
| Folder sync, shared labels, label update as a file | Rough | High setup cost |
| App lock, Hide private contacts, Privacy dashboard, Who can see, Directory provider for private names | Solid | The Directory provider is unused-looking unless another dialer queries it |

### Finding things
| Feature | Maturity | Note |
|---|---|---|
| Tools hub, Settings search, explainers, Set up the basics, What's new | Rough | Tools has stopped at 5.3 apart from Rescue call (§2.1 D1) |
| Recall / Search everything (6.0) | Solid engine, Rough reach | Only inside the Contacts tab's search (`ContactsTab.kt:238`) |

---

## 2. Critical shortcomings

### 2.1 Gaps a user hits in everyday use

**D1. The 6.x signature features are invisible to an updated user (High, S–M).**
- **Evidence:**
  - `CapabilityCatalog` rows (`core/common/.../ux/Capabilities.kt:84-235`) have no entry for:
    - Situations
    - Search everything (Recall)
    - Case files
    - the Family spam shield
    - Archived
    - Chapters
    - To talk about
    - Dead-number radar
    - "never calls you"
  - The only `since = "6.x"` row is Rescue call (`:214-215`). `AppScreen` (`:23-32`) has no SITUATIONS, ARCHIVED or CASE_FILES.
  - The What's new card says only "See what's new…" (`WhatsNew.kt:65-89`, `strings_main.xml:924`). The screen it opens lists `CapabilityCatalog.newIn(version)` first (`CapabilitiesScreen.kt:97`). After the 6.0 and 6.1 updates that list was empty, and in 6.2.x it shows Rescue call alone.
  - The glossary promises "every screen a row can open has its row" (GLOSSARY "Tools"). This holds only because the new features never became `AppScreen`s, so `CapabilityCatalogTest` can't notice the gap.
- **Impact:** the three releases that define Parley's identity (6.0–6.2) are reachable only from deep pages. Examples: Situations under Settings › Calls › Situations, Archived under Contacts ⋮, Recall as a chip inside Contacts search, the spam shield on a shared label's page. Users who updated never learn about them. Round 2 made the same finding for 4.7–5.3 (G-2/U10), and it has come back.
- **Fix:**
  - Add rows: Situations (featured), Search everything, Case files, Family spam shield, Archived contacts, Chapters, To talk about. Dead-number radar goes into the Health check summary text.
  - Add the `AppScreen` entries.
  - Add a test: every README "New in" headline has a row with `since`.
  - Let the What's new card name the top three `newIn` rows inline.
  - Settings budget unaffected.

**D2. Recall is reachable only from the Contacts tab (Medium, S).**
- **Evidence:** `recallSection` is called only from `ContactsTab.kt:238`. Navigation tabs can be reordered and hidden (`nav_tabs`, SETTINGS.md "Layout").
- **Impact:** "Who called about the boiler in March?" is a Recents question. Someone who starts in Recents, or has hidden Contacts, never meets Recall. iOS 26's Unified layout made Search one of only four toolbar items.
- **Fix:**
  - Put the "Search everything" chip in the Recents search too.
  - Add a Tools row.
  - Add a launcher shortcut "Search everything".

**D3. No birthday or anniversary line on the call screen (Low–Medium, S).** iOS 27.2 shows an anniversary or birthday banner when you call someone on the day (MacRumors, 16–17 Sep 2026). Parley has the richest dates model in the set (alternate calendars, `YearlyEvents`, `DateReminders`), but neither the telecom UI nor the caller card reads dates. A grep for "birthday" in `telecom/src/main` finds nothing. The fix is a quiet "Birthday today" or "Anniversary today" line in the caller detail, shown on the lock screen only where notes may show. It reuses the existing date code.

**D4. No help or troubleshooting in the app (Medium, M).**
- There is no help or FAQ screen (`ui/onboarding` holds two files; no help screen exists).
- A default-dialer app gets support questions about:
  - "Parley doesn't show my call screen"
  - "Calls don't ring in a Situation"
  - "Why did this call ring?"
  - battery optimisation
  - "my contact vanished after Archive"
- Today the answers are scattered across explainers, "why it rang" and the default-app note.
- **Fix:** a "Help & troubleshooting" Tools row: about ten task pages that each open the right existing tool (Test a call, Diagnostics, default-app role, Expecting a call). No setting is added.

### 2.2 Built but incomplete (6.x half-features)

**H1. Situations: switched on by hand, a Situation never ends (High, S).**
- **Evidence:**
  - `sit_on_manual` reads "On until you turn it off".
  - `SituationsController.kt:96`: "it stays on until switched off by hand".
  - Meeting's "Who may ring" sends everyone else to "ring silently and show as missed calls" (`sit_ring_sub`).
  - The reminder that it is on is the home chip (`sit_chip_on`) and the Quick Settings tile. Nothing tells you outside Parley.
- **Impact:** a "Meeting" started at 10:00 and forgotten silences the school, the courier and the doctor for the rest of the day. iOS Focus and Android modes both offer "for 1 hour" or "until …".
- **Fix:**
  - Switching on by hand (tile, chip, Situations page) asks: For 1 hour · Until [the end of its window or 18:00] · Until I turn it off. This is a per-activation choice, not a setting. It reuses the existing WorkManager window job.
  - While a Situation that silences anyone is on, show a silent ongoing notification ("Meeting is on · Turn off").
- **Also:**
  - `DeviceTrigger` (`Situations.kt:30-40`) has no roaming trigger, so "Travelling" is manual only. `READ_PHONE_STATE` is held, and `AssistedDial` already knows when the phone is roaming. Add `ROAMING` (S).

**H2. Archive: one at a time, no Undo, and two surprises the confirmation doesn't state (High, S–M).**
- **Evidence:**
  - `BulkAction` (`core/common/.../people/BulkActions.kt:12`) has no ARCHIVE, so there is no multi-select archive. The round-2 "Spring clean" (stale contacts from the Health check → Keep / Archive / Delete) was not built: a grep finds no archive path in `ui/health`.
  - After archiving, Parley shows a toast only (`ArchiveActions.kt:89`). There is no Undo snackbar, which breaks round 2's U8 "Undo everywhere".
  - The confirmation text (`strings_archive.xml:6`) says the contact leaves "your contact lists, search, widgets and other apps". It does not say:
    - The contact is deleted from the synced account, so from Google Contacts on the web and on every other device. The post-hoc toast says "Other apps lose it after the account's next sync" (`:8`).
    - A photo larger than 512 KB is kept only as its thumbnail (`ArchiveStore.kt:48`). That breaks the 4.3 and 5.3.1 promise that photos are "kept whole" and "originals kept exactly as picked".
  - Unarchive writes a new raw contact, so links other apps held (chat apps, account IDs) don't come back.
- **Impact:** a user who "archives" a former colleague finds them gone from Gmail and their laptop, and their photo degraded, without having been told. That is data loss at the edges of a "never lose a contact" app.
- **Fix:**
  - State both facts in the confirmation.
  - Keep the original photo; the sealed record can hold it, as backups do.
  - Add Undo, which runs Unarchive into the same account.
  - Add ARCHIVE to bulk actions.
  - Add "Archive" to the Health check's stale group.

**H3. Case files have no list and no outcome (Medium, S).**
- **Evidence:** the only route is `Destination.Case(id)` (`HistoryNavigation.kt:38`). There is no `AppScreen` and no index. A case doesn't record whether it is open, waiting for them, or resolved.
- **Impact:** "What's happening with my insurance claim and my broadband complaint?" can't be answered in one place. A case's useful life is weeks, and without a list it is found only by remembering the organisation.
- **Fix:**
  - A "Case files" list (Tools row) sorted by last activity, with an open promise count.
  - A one-tap status (Open · Waiting for them · Resolved) that the PDF includes.

**H4. "Who can see / Directory" and the device-dependent 6.x pieces have no device steps (High, M, owner time).**
- **Evidence:** `DEVICE_CHECKLIST.md` has no line for any 6.x feature: a grep for Situation, Rescue, Archive, Chapter, Agenda, Case file, "never calls" or network gives 0 matches. TESTING.md §39–43 hold full steps for them.
- **Device-dependent pieces with no step:**
  - Rescue call relies on `setAndAllowWhileIdle` (inexact; `RescueCalls.kt:313-316`), so a "15 minutes" call may come late in Doze.
  - Situation windows end on an inexact WorkManager job (`SituationTriggers.kt:46`). The call path re-checks before each call, which is good.
  - Car and Bluetooth triggers match by product name.
  - Dead-number radar depends on carrier disconnect causes.
  - Network names depend on the carrier's CNAP.
- **Fix:** see §5 (R1).

### 2.3 Correctness risks seen in code

**C1. "This number never calls you" disarms itself after the first inbound call, including the scam call itself (High, S).**
- **Evidence:** `NeverCallsYou.onlyYouCalled` requires `past.all { … == OUTGOING }` (`NeverCallsYou.kt:93-94`). `NeverCallsYouFacts.pastCalls` reads every logged call with the line (`NeverCallsYouFacts.kt:93-98`). "Was it a scam?" on the post-call card only shows the signs and records nothing (`PostCallCard.kt:78`).
- **Impact:** a spoofed "bank" call that rings once, answered or missed, becomes an "incoming call from the bank". From then on the warning never shows on that line, which is exactly when a scam campaign's follow-up calls arrive.
- **Also:**
  - A user who installed recently gets no warning until Parley's call copy reaches back past their first outgoing call (`reachesBack`, `:115`). This is correct, but nobody is told.
  - The organisation word lists are English only (`:21-33`). This is acceptable under the English-only rule, but the company field and "Company main" type carry non-English users.
- **Fix:**
  - "Was it a scam?" → "It wasn't them" marks that call, and later calls with the same "wasn't them" mark are left out of `past`.
  - Add a per-contact "They never call me", round 2's design, which makes the warning permanent for that organisation. It is a choice on the contact, not a setting.
  - In the number's history, say "Parley will warn you once it has your calls since [date]".

**C2. The Archive photo shrink (H2) contradicts a stated product promise.** It is listed here as well because it loses data without being asked.

**C3. Agenda items and promises are the same lines (Low, S).** `Agenda.open` is `Promises.open` (`Agenda.kt:26`), so every open promise in a note for calls ("[ ] pay Ana back") also shows under the caller as something to talk about, with a count on the lock screen. This is by design (GLOSSARY "To talk about"), but it surprises anyone who uses promises as a to-do list. The fix is to render items differently and leave the storage as it is: on the call screen, show only items added with "Add something to talk about", or call the section "Open with them".

**C4. VoIP calls in Android's call log would show as carrier calls (Medium, S–M, confidence medium).**
- **Evidence:** `Calls.PHONE_ACCOUNT_COMPONENT_NAME` is read (`CallLogRepository.kt:154`, `CallHistory.kt:566`) but used only to copy it into backups and the archive (`BackupArchive.kt:44`, `ArchivedCalls.kt:42`). Nothing decides what a row is from it.
- **Impact:** apps that log self-managed calls to the system call log (an option since Android 14; One UI 9 shows VoIP calls) would appear in Recents as phone calls, and "Call back" would dial through the carrier.
- **Fix:**
  - Classify rows by component: telephony versus a known app package.
  - Show the app's badge, and make Call back open that app ("Message or call on…").
  - Confirm on a One UI 9 or Android 17 device first.

**C5. Crash follow-up is weak for testers (Medium, S).**
- **Evidence:** crash capture is off by default (`CrashStore.kt:20`, `K_ENABLED` false). Diagnostics already reads `getHistoricalProcessExitReasons` (`Diagnostics.kt:98`), but only when the user exports diagnostics.
- **Impact:** in the device-testing phase, the first crash in the call path leaves no report.
- **Fix:**
  - At start, if Android reports a crash or ANR since the last run, show one card: "Parley stopped unexpectedly · Save a report". It needs nothing stored beforehand.
  - Turn capture on by default in debug builds.

### 2.4 Owner-deferred items (noted, not planned)
- NFC exchange and ViLTE video calls: skipped (CA7 §8.7).
- Phase B: public release, licence, SECURITY.md, PRIVACY.md, reproducible-build proof, F-Droid, audit. Deferred until the app is polished.
- X9 Courtesy hours and X11 Vouched introductions: unbuilt. A grep finds no implementation. Re-evaluated in §4.3.
- Translations: English only, by decision.

---

## 3. Competitive gaps (2026) that fit Parley's constraints

The rule for this list: offline, no new permission, no call audio. Features that need call audio, a server or an account are listed at the end and should not be chased.

| # | Who has it (2026) | Gap in Parley | What it would take | Effort |
|---|---|---|---|---|
| G1 | iOS 27.2: birthday or anniversary alert when you call someone on the day | No dates on the call screen | One caller-card line from `YearlyEvents`/`DateReminders`, also on the outgoing screen (D3) | S |
| G2 | iOS 27 Call Context: booking codes from Mail shown when you call a business | Case files hold reference numbers, but only ones typed during a call | "Booking pocket" (§4, #6): share any text to Parley and the codes land in that organisation's case file | M |
| G3 | Google Phone "Wait times" (server-side) | Case files know your hold times but don't summarise them | "Queue memory" (§4, #4) | S |
| G4 | Truecaller: suggestions of who to call based on habit | GoodTime and Circle exist, but there is no "now" surface | "Next up" (§4, #5) | M |
| G5 | iOS 26 Unified layout: Search as a top-level item | Recall lives inside Contacts search only | D2 | S |
| G6 | One UI 9: VoIP calls in the call log | Rows not classified | C4 | S–M |
| G7 | Android 17 system contact picker (`ACTION_PICK_CONTACTS`; legacy `ACTION_PICK` is upgraded to it) | Parley's `PICK` filter (`AndroidManifest.xml:309`) may no longer be offered. `targetSdk 36`; `who_picker_text_37` exists | Verify on Android 17. Say in Privacy that the system picker never shows private contacts, which is a privacy plus. Update "Who can see" | S |
| G8 | iOS Focus and Android modes: "for 1 hour" | Situations never end when switched on by hand | H1 | S |
| G9 | Cardhop: a one-line natural-language add or edit ("Ana birthday 3 May") | Paste details covers adding, not editing | Optional: accept "name + field" lines in Paste details for an existing contact | M (low priority) |
| G10 | Samsung and Google calling cards and posters you send | My card is signed but carries no picture or poster | Leave it: little value without a public user base | — |

**Not to chase** (they need audio, a cloud or system rights):
- iOS 26 Call Screening (asks the caller's name and reason) and Hold Assist (listens for hold music).
- Samsung Call Screening (One UI 8.5).
- Pixel on-device scam detection and the RCS fake-call check.
- Truecaller Assistant and WhatsApp caller ID (needs a notification listener).
- Call recording and transcription.

Parley's honest substitutes already exist: "Text me your name", Expecting a call, I'm on hold, the safe word, Check it's really them, "never calls you". §4 strengthens them.

---

## 4. New signature features

Each feature below reuses pieces Parley already has. It works offline, needs no new permission and adds **no Settings row**: each is an action, a per-use choice or a line on an existing surface, so the 147/148 ceiling holds.

### 4.1 The ideas

**1. Promise a time (S).**
- **Problem:** you decline a call in a meeting, text "call you later" and forget. The other person doesn't know when.
- **How it works:**
  - Decline, the missed-call notification and the post-call card get "I'll call you at…" with chips: 15:30 · In 1 hour · This evening (their time). Times come from the Situation's end, GoodTime, and their local time.
  - One tap opens the SMS app with "Can't talk now, I'll call you at 15:30 (your time 22:30)" and puts a To call item at 15:30.
  - If they ring again before then, the caller card says "You said you'd call at 15:30".
- **Reuses:** quick replies and the SMS intent, Remind me / `ToCall`, the To call "their time" logic (`to_call_their_time`), GoodTime, the Situation window.
- **Risks:** time-zone wording; the SMS composer still needs a tap, which is correct.

**2. Callback watch: "They'll call me" (S–M).**
- **Problem:** "The hospital will call you back by Friday", from a number you don't know. Screening silences it (the top complaint about iOS and Samsung screening), or it never comes and you don't notice.
- **How it works:**
  - On the post-call card and in a case file, "They'll call me back" with a by-date.
  - Parley opens an Expected window for that organisation: its saved numbers, plus unknown numbers if you choose "from any number".
  - If no call has come by the date, To call shows "St Mary's hasn't called back · Call them".
  - An expected callback also quietens "never calls you" for that window, and flags any call outside it.
- **Reuses:** `ExpectedCalls`/`ExpectedWindow` (already has `TO_CALL` and `NOTE` sources), promises, `ToCall`, Case files, `NeverCallsYou`.
- **Risks:** windows that let unknown numbers ring must stay below rules and lists, as `ExpectedWindow` already guarantees.

**3. Fresh-number warning ("number passport") (S–M).**
- **Problem:** a common scam step is to get you to change a saved number ("our bank has a new number", "Mum's new phone"), or for another app or a sync to change it. After that, the scammer's calls show a trusted name.
- **How it works:**
  - When a saved contact calls from a number that was added or changed recently (say in the last 14 days) by something other than you typing it, the caller card says so: "This number was added to Mum 3 days ago, from a pasted message", or "changed by another app", or "changed by a shared label member".
  - It offers Check it's really them, which calls the older number the snapshot still has.
  - On the contact page, each number shows where it came from (typed, QR, pasted text, card update, shared label, import) and since when.
- **Reuses:** `Provenance` ("why did this change"), the undo journal, daily snapshots (the number wasn't there last month), the shared-label change history, `CardUpdates`, `VerifyCallBack`.
- **Risks:** false alarms after a genuine number change. Show it calmly and only for the first few calls. Signed card updates count as trusted.

**4. Queue memory: the best time to call them (S).**
- **Problem:** you call the council and wait 25 minutes. Google's Wait Times needs its servers.
- **How it works:**
  - From each case file's hold times and call times: "Your last 4 calls waited 6–25 min. Shortest before 9:30 on weekdays."
  - Shown in the pre-call peek and on the organisation's page, with "Remind me at 9:00" (To call).
  - Menu keys are offered as today.
- **Reuses:** `CaseTimeline` hold times, `HoldMode`, `MenuMemory`, the GoodTime windowing code, `ToCall`.
- **Risks:** a small sample. Show it only after 3 or more calls, and say how many it is based on.

**5. Next up: one "now" strip (M).**
- **Problem:** what to call, and when, is spread over To call, Circle, birthdays, the agenda, case files and Chapters.
- **How it works:** the existing To call strip in Recents (and in Favourites) becomes "Next up", with at most three lines, ranked:
  1. calls owed now, and times you promised;
  2. today's birthdays and anniversaries, which also go on the caller card (G1);
  3. a person whose GoodTime window is open now and whose Circle rhythm is due;
  4. a case past its promised date;
  5. a Chapter ending this week.

  Each line has its one action. Nothing shows when nothing is due.
- **Reuses:** `ToCall`, `DateReminders`/`YearlyEvents` (alternate calendars), `GoodTime`, `KeepRhythm`, Agenda, Case files, Chapters.
- **Risks:** clutter. It replaces the strip rather than adding one, and is capped at three lines.

**6. Booking pocket: Call Context without reading your mail (M).**
- **Problem:** iOS 27 shows the flight code when you call the airline by reading Mail. Parley can't read mail, and shouldn't.
- **How it works:**
  - Share any text to Parley (a booking email, an SMS, a copied PDF): "Add to a case file".
  - Paste details finds the organisation's phone numbers, reference-like codes, dates and names. You tick what to keep.
  - The codes go into that organisation's case file, sealed. The case is created for a saved organisation, or a temporary private contact is made for an unsaved one.
  - When you call that number, or it calls you, the codes show on the call screen with Copy and a large grouped "read it out" view.
  - Dates become expected calls (#2).
- **Reuses:** the `text/plain` share target (`AndroidManifest.xml:350-356`), `PasteLines`/`PasteWords`, Case files' sealed references, Recall (codes are searchable), `ExpectedCalls`, temporary contacts.
- **Risks:**
  - Parser false positives; the tick list handles them.
  - The house rule stays: never type references into menus.

**7. Call round (M).**
- **Problem:** you have to tell eight people something (a funeral, a surgery, a cancelled event, a team change), and then lose track of who knows.
- **How it works:**
  - From a label or a selection: "Start a call round". You write one line to tell them; it becomes an agenda item for each person.
  - Parley shows the next person after each call ("Next: Ben · Call"), never dialling by itself.
  - It marks each person Reached, No answer (adds To call "later today") or Skip, and ends with a summary: "6 reached, 2 to try again". The round can be resumed from Next up.
- **Reuses:** labels and selection, Agenda, `ToCall`, the post-call card, Chapters (a round can belong to a Chapter).
- **Risks:** it must never place a call without a tap; emergency rules are unchanged.

**8. Situations that suggest themselves (S–M).**
- **Problem:** few people set up Situations; most people's patterns are visible in their own calls.
- **How it works:** weekly, at most one card from your own history:
  - "You decline most calls Tue 10–11. Make that a Meeting window?"
  - "'Car Kit' connects every weekday at 8:10. Use it for Driving?"
  - "You've been roaming for 3 days. Turn on Travelling?"

  Accept or Never.
- **Reuses:** `Screening.suggestions` (it already mines call history), `SituationTriggers`, the Bluetooth device names it already sees, roaming state.
- **Risks:** nagging. Cap it at one a week, and show none until 4 weeks of history exist.

**9. Check-in timer (S).**
- **Problem:** walking home late or meeting a stranger: "if I don't confirm, someone should know". Parley can't send a text by itself, by design.
- **How it works:**
  - "Check in at 23:00" rings a rescue-style call at that time; answering means "I'm fine".
  - If it isn't answered, a high-priority notification and the lock-screen tile offer one tap to "Text Sam: I may need help, call me", prefilled for your family helper, plus Call Sam.
  - Duress-aware.
- **Reuses:** Rescue call (screen, alarm, ringer), family helpers, the SMS intent, the Quick Settings tile.
- **Risks:**
  - It mustn't promise what it can't do. Say plainly that nothing is sent without your tap.
  - The alarm is inexact on Doze (H4), so it needs device testing.

**10. Care circle: shared check-ins in a family label (L).**
- **Problem:** three siblings looking after one parent each think another one called this week.
- **How it works:**
  - In a shared family label, each member may share, per person, "last spoke with Grandma: yesterday, about 10 min" (date and a duration bucket only, never notes), plus shared "to talk about" items ("ask about the new pills").
  - Grandma's page and caller card show "Sam called her yesterday". Her Circle rhythm counts the family's calls.
- **Reuses:** shared-label sync, signatures and membership (`common/sync/shared`), the Family spam shield's verdict transport, Agenda, Circle rhythms, the label update-as-file mode.
- **Risks:**
  - Privacy: strictly opt-in per label and per person, and never for private contacts.
  - It is only as live as the folder sync, about hourly.
  - Adoption needs every member on Parley and Syncthing, the same barrier as the shield.

**11. Official lines pack (L, mostly curation).**
- **Problem:** "Is this really my bank's number?" Today Parley can only say what *your* history shows.
- **How it works:**
  - Signed list packs, delivered through the Lists companion as spam lists are, carry the inbound and outbound numbers that banks, tax offices and health services publish, per country.
  - An organisation contact shows "Matches the number HSBC publishes ✓", or "Not a number HSBC publishes".
  - Check it's really them prefers a pack number.
- **Reuses:** `ListPack` signatures, the companion, `VerifyCallBack.organisations`, `NeverCallsYou`.
- **Risks:** stale data, and implied endorsement. Word it as a fact about the list and date it. The cost is curation, not code.

### 4.2 Ranking by user value per effort

| Rank | Feature | Value | Effort | Why this place |
|---|---|---|---|---|
| 1 | Promise a time | High, daily | S | Turns the commonest moment (declining) into a kept promise |
| 2 | Callback watch | High | S–M | Fixes the top complaint about screening; strengthens "never calls you" |
| 3 | Fresh-number warning | High (safety) | S–M | Closes the "save our new number" scam path that every saved-name ID trusts |
| 4 | Queue memory | Medium–High | S | The data already exists in case files |
| 5 | Next up | High, daily | M | One place for "what now"; absorbs the birthday gap |
| 6 | Booking pocket | Medium–High | M | An offline answer to iOS 27 Call Context |
| 7 | Call round | Medium | M | Rare but painful; clean reuse |
| 8 | Situations that suggest themselves | Medium | S–M | Gets Situations used |
| 9 | Check-in timer | Medium (safety) | S | Must be worded honestly |
| 10 | Care circle | High for some families | L | Same adoption barrier as shared labels |
| 11 | Official lines pack | Medium | L (data) | Value depends on curated data |

### 4.3 X9 and X11 re-evaluated

**X9 Courtesy hours** (preferred calling hours carried in the signed card).
- It only reaches people who run Parley, received your signed card and linked it. Before a public release, that population is close to zero, and even afterwards it stays small.
- The caller's side is already mostly covered locally:
  - their local time on the call screen and in To call ("After 6 pm their time");
  - GoodTime's learned answer window.
- **Recommendation:** drop it as a feature. Show "Usually answers 18–21 their time" in Next up (#5) and the pre-call peek. After Phase B, revisit one optional "best times" field in My card.

**X11 Vouched introductions** (signed "Introduced by Sam").
- It has the same network-effect problem.
- Its real problem ("a number someone forwarded later calls as unknown, and I don't know who it is") is better solved locally:
  - The fresh-number warning (#3) and number provenance record "pasted from Sam's message, 12 Mar".
  - Recall already finds pasted and messaged numbers.
- **Recommendation:** keep it deferred until after Phase B. Its local half (an "Introduced by" provenance line on save) comes for free with #3.

---

## 5. What still separates Parley from a polished, release-worthy app

| # | Gap | Evidence | What "done" looks like | Effort |
|---|---|---|---|---|
| R1 | **No device run, and no Android 17** | `DEVICE_CHECKLIST.md:3`; 76 lines of androidTest; `targetSdk = 36` (`app/build.gradle.kts:33`); Android 17 stable since 16 Jun 2026; One UI 9 (Android 17) rolling out since 16 Sep 2026 | Extend the checklist with about 12 lines for 6.x (Situations on and off by window and car; a rescue call after 15 min in Doze; archive and unarchive with Google; a "never calls you" call; shield verdicts between two phones; Chapter end; network name on an Indian or CNAP carrier; dead-number radar). Run it on Pixel / Android 17, Samsung / One UI 8 or 9, and one other OEM (Xiaomi or Motorola, which are aggressive on background work). Record results in the file | M (owner time) |
| R2 | **Benchmarks and baseline profile never run on a device** | PERFORMANCE_BENCHMARKS.md "How to run on a device"; `baseline-prof.txt` is hand-made | One recorded run of the three macrobenchmarks and a generated profile | S–M |
| R3 | **Crash and ANR follow-up** | C5 | Exit-reason card at start; capture on in debug | S |
| R4 | **Upgrade and restore safety across versions** | Room migrations are tested (`AppDatabaseMigrationTest`), sealed formats have fixtures (`SealedFormatFixturesTest`), but test resources hold no backup files from earlier releases (only `csv`, `fuzz`) | Golden backups written by 5.0, 5.7 and 6.0 builds, restored in a test; an on-device update test from 5.7 to 6.2.1 with real data (vault, archive, Situations active) | M |
| R5 | **Discoverability and What's new** | D1, D2 | Every 6.x feature has a Tools row; What's new names them | S–M |
| R6 | **Help** | D4 | Help & troubleshooting pages | M |
| R7 | **Finish the 6.x edges** | H1–H3, C1 | As in §2 | S–M each |
| R8 | **First run for a new user** | "Set up the basics" covers who may ring and layout | Add an optional last step: one Situation (Night) and Import (already in "Coming from another phone?"). Keep the step count | S |
| R9 | **Automated accessibility checks** | Manual only (round 2 #14) | `AccessibilityChecks` in the instrumented smoke suite once a device or emulator runs | S |
| R10 | **Companion app quality** | lists-updater has 0 tests and the only network code | Unit tests for download, verify and handoff | S–M |
| R11 | **Owner items for later** | Phase B: signing key offline (C-03), licence, policies, reproducible build, F-Droid | Deferred by the owner, listed only so it isn't forgotten | — |

**What is already release-quality, so the plan must not break it:**
- the screening engine, its trace and Test a call;
- History & undo, snapshots and the sync watchdog;
- the duress model after 5.4;
- Situations' restore logic;
- Recall's privacy gating;
- the settings budget discipline;
- the house wording (glossary).

---

## 6. Prioritized product plan

Each step is one release, built, reviewed and fixed as before. Phase B stays deferred.

| Release | Theme | Contents |
|---|---|---|
| **6.2.2** | **Finish 6.x** (no new features) | D1 Tools rows and What's new; D2 Search everything in Recents; H1 Situations "for how long" + ongoing notice + roaming trigger; H2 Archive honesty (confirmation text, original photo kept, Undo, bulk, Health check path); H3 Case files list and status; C1 "never calls you" (scam mark, "They never call me"); C3 agenda section naming; C5 crash and exit-reason card |
| **6.3** | **Proof** | R1 device checklist for 6.x and the run on Android 17, One UI and one more OEM; R2 benchmarks and profile; R4 golden backups and an upgrade test; C4 VoIP rows and G7 Android 17 picker (both verified on the device run); R9; R10. Fix what the device run finds before anything else |
| **6.4** | **Daily signature, wave 4** | #1 Promise a time, #2 Callback watch, #3 Fresh-number warning, #4 Queue memory, D3/G1 dates on the call screen |
| **6.5** | **Daily signature, wave 5, and help** | #5 Next up (absorbs the To call strip), #6 Booking pocket, D4 Help & troubleshooting, R8 first-run step |
| **6.6** | **Occasional and family** | #7 Call round, #8 Situation suggestions, #9 Check-in timer (after the rescue-call device results) |
| **Later / after Phase B** | Network-effect features | #10 Care circle, #11 Official lines pack (needs a data owner), X9 and X11 if there is a Parley user base |

**Rules for every step:**
- No new permission, and nothing online.
- The 147 (148) settings ceiling holds: every feature above is an action or a per-use choice.
- Menus stay at seven items or fewer.
- Each feature lands with its Tools row, so D1 can't happen again.
- Each feature lands with its device checklist line, so H4 can't happen again.

---

## 7. Nits
- The "Drive profile" Tools row (`Capabilities.kt:196`) still opens the drive profile screen, while SETTINGS.md 6.0 says the drive profile is set from Situations. Point the row at Situations, or say so in its summary.
- "Introduce myself…" (`Capabilities.kt:186`) and "Plan minutes per SIM" (`:202`) remain Tools rows for very rare jobs. Fold them under "n more" (they are), and consider removing them at the next budget pass.
- I'm on hold (`HoldMode.kt`) is a timer with two buzzes. Either write its hold time into the case file automatically (it may already feed `CaseTimeline`; verify) or say what it does in its row.

---

## Sources (web, checked 8 Oct 2026)
- [MacRumors: iOS 26 Phone app guide](https://www.macrumors.com/guide/ios-26-phone-app) · [iGeeksBlog: What's new in the Phone app with iOS 26](https://www.igeeksblog.com/whats-new-in-the-phone-app-with-ios-26/)
- [MacRumors: iOS 27 Phone and FaceTime](https://www.macrumors.com/guide/ios-27-phone-facetime/) · [9to5Mac: iOS 27 Call Context](https://9to5mac.com/2026/07/24/ios-27-adds-new-phone-app-feature-thats-an-instant-favorite/) · [MacRumors: iOS 27.2 anniversary alerts](https://www.macrumors.com/2026/09/16/ios-27-2-could-save-your-marriage/)
- [Samsung Newsroom: One UI 9 rollout](https://news.samsung.com/global/samsung-begins-official-rollout-of-one-ui-9-bringing-the-latest-galaxy-experiences-to-more-devices) · [SamMobile: Galaxy S26 Call Screening explained](https://www.sammobile.com/news/galaxy-s26-call-screening-explained-how-does-samsung-filter-spam-calls/) · [Tech Advisor: One UI 8.5 features](https://www.techadvisor.com/article/3125998/samsungs-one-ui-8-5-update-to-bring-free-features-to-galaxy-phones.html)
- [Mezha: Google Phone Calling Cards](https://mezha.ua/en/news/google-phone-calling-cards-304435/) · [PC-Welt: Pixel scam detection and fake-call detection](https://www.pcwelt.de/article/3165851/android-telefonbetrug-schutztechniken-pixel-call-screening.html) · [TechCrunch: Google Wait Times / Direct My Call](https://techcrunch.com/2021/10/19/google-makes-calling-businesses-less-painful-with-features-for-seeing-wait-times-phone-tree-options-and-more)
- [APKMirror: Truecaller 26.x release notes](https://www.apkmirror.com/apk/true-software-scandinavia-ab/truecaller-caller-id-block/truecaller-trusted-caller-id-26-25-8-release/)
- [F-Droid: Fossify Contacts](https://f-droid.org/en/packages/org.fossify.contacts/) · [Flexibits: Cardhop release notes](https://flexibits.com/cardhop-ios/releasenotes)
- [Android Developers Blog: Contact Picker](https://developer.android.com/blog/posts/contact-picker-privacy-first-contact-sharing) · [Android Authority: Android 17 stable rollout](https://androidauthority.com/android-17-stable-rollout-features-3675016)
