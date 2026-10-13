# OpenContacts and Logger study: where Parley stands, what it lacks, and what could go further

*13 Oct 2026. A read-only study for the owner. It compares Parley 6.9.0 (`61dada60`) with OpenContacts 32.0 and Logger 3.4.2, the versions F-Droid offers today. Both apps' F-Droid pages, changelogs, READMEs and sources were read (OpenContacts `master` at `f047ad9`; Logger `main` at `35c4f3a`). Nothing was built or run, and no code was copied. Round 1 ([COMPETITIVE_ANALYSIS.md](COMPETITIVE_ANALYSIS.md)) read an older OpenContacts, and round 3 ([COMPETITIVE_ANALYSIS_3.md](COMPETITIVE_ANALYSIS_3.md) §2.6, H1–H9) read Logger 3.4.2. This study maps every feature to Parley as it is now, records what changed since those rounds, and proposes new features. IDs use the prefixes **OG** (gaps taken from these two apps) and **NX** (new ideas), with owner decisions **O1–O11**, so they don't collide with AUDIT_3's SA/PF/LC/UX/GP/SG, CIRCLES_STUDY's CI/FC, or the competitive analyses' letters.*

## 1. Summary

**Where Parley stands.** Parley already has nearly everything both apps do, usually more carefully, and with fewer permissions. Of OpenContacts' 50 features, Parley is better at 23, equal at 15, partial at 2, and lacks 7; 3 don't apply to a phone app. Parley chose to leave out 6 of those 7 (CardDAV, a partner-app API, translations, and overrides for font size, screen rotation and the clock format). Of Logger's 30 features, Parley is better at 11, equal at 11, partial at 4 and worse at 1 (a custom date range), and lacks 3, one of them (translations) by design. The two main ideas behind these apps are both built into Parley now. OpenContacts' private phonebook became the private vault, archive and temporary contacts. Logger's "call history as data" became Parley's call archive, Insights, exports and CSV import, which round 3 adopted.

**What we lack that is worth building** (all small, offline, no new permission, no new setting):

1. **OG-1. Archive should take the person's name out of Android's call log** (*verified bug, privacy*). Reading Logger showed this one. An archived contact is promised to be "out of other apps". But every old call with them in Android's call log still carries their name in `CACHED_NAME`, and any app with call-log access can read it, Logger included. Parley also writes names back into that log on three paths: undoing a call delete, restoring a backup when the archive is off, and importing calls from CSV. **Fix in 7.0.**
2. **OG-2. A custom date range in the Recents filter** (Logger). Parley offers only fixed periods. "All calls in March for my accountant" can't be filtered, so it can't be exported either.
3. **OG-3. A totals line on a filtered view** (Logger's "total call duration" and "calls per day", without its switches). For example: "23 calls · 3 h 12 min · 4 missed".

**Ideas that go beyond both apps** (section 5, ranked). Each one builds on parts Parley already has:

1. **NX-1. A call record you can hand over.** For one number or case: every call, including blocked and silenced attempts with Parley's reason, as a PDF and CSV with a fingerprint printed on each page. It is for harassment, disputes with landlords or companies, and complaints.
2. **NX-2. Who can see your calls.** The "Who can see your contacts" check, extended to apps that can read your call history. It fits with OG-1.
3. **NX-3. Temporary until the job's done.** A temporary contact that expires N days after your **last** call, not N days after you saved it.
4. **NX-4. In memory.** When someone's date of death is set, Parley offers to keep their calls and **their voicemails** for good, sealed, with a quiet page about them.
5. **NX-5. Talk time per person, rounded for billing.** An export for freelancers, tutors and lawyers. It does what Logger's "round calls to minutes" tried to do, without changing the stored durations.
6. **NX-6. A pinned "Call back" shortcut** for people who use Voice Access or switch access. It is the useful part of OpenContacts' voice-command add-on, without its SDK.
7. **NX-7. Check a phone bill** (later): compare a carrier's itemised bill with Parley's own record of calls.

**Not recommended:** CardDAV sync, a partner-app API, the MakeItAccessible SDK, translations, an in-app font size, portrait lock, a 12/24-hour override, Logger's 0–100 interaction score (already turned down as C1), file-name tokens, tracking numbers that aren't saved, and a "Sync call log names" button.

## 2. The two apps

| | OpenContacts | Logger |
|---|---|---|
| What | A private phonebook with its own database, separate from Android's contacts. It shows caller ID over the system dialer with a floating overlay, and keeps its own copy of the call log (`opencontacts.open.com.opencontacts`) | A call-log viewer with filters, analytics, a "tracklist", export and import (`com.logger.app`), built with Flutter |
| Version read | 32.0 (versionCode 32, F-Droid 10 Feb 2026), plus `master` up to 9 Oct 2026 (redesigned card-style caller-ID overlay, accessibility labels, crash fixes) | 3.4.2 (62, F-Droid 15 Feb 2026), plus `main` up to 2 Jul 2026 (an "unknown contacts only" filter, tracking by contact name) |
| Source | <https://gitlab.com/sultanahamer/OpenContacts>, about 11.5k lines of Java read (activities, data stores, services, preferences, strings, manifest, changelog v1–v32) | <https://github.com/Sanmeet007/logger>, about 12.2k lines of Dart read (screens, providers, utils, the English `.arb` strings), plus the Kotlin `MainActivity` |
| Licence | **GPL-3.0-only** | **GPL-3.0-or-later** |
| Permissions | `INTERNET` (CardDAV), `SYSTEM_ALERT_WINDOW` (overlay), `QUERY_ALL_PACKAGES` (new in 32.0), read and write shared storage, `READ_CALL_LOG`, `READ_PHONE_STATE`, `CALL_PHONE`, `WAKE_LOCK`, notifications | `READ_CONTACTS`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_PHONE_STATE`, read and write shared storage |
| Not a phone app | Answering and the in-call screen stay with the system dialer. Call filtering uses `CallScreeningService` (Android 10+) | Doesn't handle calls at all |

Both licences are compatible with Parley's GPL-3.0, so borrowing would be legally possible with attribution. Nothing here needs it.

## 3. Their features against Parley

"Better" or "worse" means Parley compared with the other app. "Lack (by design)" means Parley left the feature out on purpose: a principle or an owner decision rules it out.

### 3.1 OpenContacts

**Contacts and privacy**

| # | OpenContacts | Parley | Verdict |
|---|---|---|---|
| 1 | Every contact kept in its own database, unseen by other apps | Private contacts per person: sealed with Keystore, found by an HMAC of the number, and still the same contact everywhere. Archive, for people you want out of the address book but still named | **Better** |
| 2 | Caller ID as a draggable overlay, now a card | Parley's own call screen names private and archived callers, on the lock screen too, with no overlay permission | **Better** |
| 3 | Switch to turn off caller ID for unknown numbers | Not needed: no overlay | n/a |
| 4 | Its own copy of the call log, so names show | Private call history (moved out of Android's log within about a second) and the encrypted call archive | **Better** |
| 5 | Contacts with no number; custom field types; full address; birthday, website, notes, emails | All of Android's fields, kept lossless, plus custom fields, relations, alternative calendars and handles | **Better** |
| 6 | Groups, with names sorted in the editor | Labels with policies, pages, sharing and Chapters | **Better** |
| 7 | Favourites; the favourite flag survives export and import (fixed in 32.0) | Android's star, chosen favourites kept apart from Do Not Disturb stars, round-trip tested | **Better** |
| 8 | Preferred number (kept on export since 32.0) | Default number and email | Equal |
| 9 | Temporary contacts: a checkbox, deleted after 30 days | 1, 7 or 30 days or a custom time; private by default; can take their call history with them; asks before deleting; Extend and Keep permanently | **Better** → NX-3 goes further |
| 10 | Merge by hand; automatic merge when a number matches | Find & merge duplicates (one Undo), a warning when a contact already exists, import that skips duplicates | **Better** |
| 11 | Delete all contacts | Select all › Delete (with Undo); Delete all data | Equal |
| 12 | Import vCard (also encrypted); handles `.vcf` opened from other apps | vCard 2.1–4.0, CSV with column mapping, an import report, "Coming from another phone?" | **Better** |
| 13 | Export vCard; encrypted ZIP with the password kept in plain preferences | Plain or encrypted vCard (passphrase not stored), CSV, notes | **Better** |
| 14 | Weekly automatic export | Scheduled, signed, encrypted backups, checked after writing and rotated | **Better** |
| 15 | Choose an export folder | Android's file picker, no storage permission | Equal |
| 16 | CardDAV sync (Nextcloud, Radicale, Synology, SOGo; plain HTTP and "ignore SSL errors" options) | Encrypted folder sync through Syncthing or Nextcloud, with no internet permission | **Lack (by design)**: no internet |
| 17 | Share as text or vCard | Share… (vCard, plain or secure QR), Copy as text, My card | **Better** |
| 18 | Handles other apps' "add contact" and dial requests | Insert, Edit, Insert-or-edit, the contact picker, `tel:` | **Better** |
| 19 | One-contact dial widget | Direct-dial widget and the People widget (shows only counts while locked) | **Better** |
| 20 | Home-screen shortcut that calls, or (32.0) opens the contact | Pinned shortcuts to call, message or open (`Shortcuts.Kind`), plus menu shortcuts | Equal |
| 21 | WhatsApp, WhatsApp Business, Telegram and Signal buttons; a default chat app | Message or call on… for about 20 messengers, opened directly; a remembered app per person; Reach via apps | **Better** |
| 22 | Address opens maps; birthday adds a calendar event; website opens; long-press copies | Map links to the exact spot, copy anywhere, and birthday reminders with no calendar permission. No "add birthday to calendar" | **Partial**: not needed (Parley's own reminders) |
| 23 | Partner-app API (AIDL): another app with a granted permission can fetch **all** names and numbers, or names for given numbers | A Directory provider answers one exact number per lookup, per app approved inside Parley, with an hourly limit and a log. The broader lookup provider went in 5.6 because no app used it | **Lack (by design)**: Parley's way is narrower and safer |
| 24 | MakeItAccessible voice actions: "call first Adam", WhatsApp, message, Telegram, redial, each spoken back | TalkBack labels, Voice Access on the answer slider, spoken call status | **Lack** → NX-6 |

**Keypad, search and the call log**

| # | OpenContacts | Parley | Verdict |
|---|---|---|---|
| 25 | T9 search (can be turned off), Pinyin T9, accent-insensitive search, 1 = dot, 0 = space | T9 in 8 keypad alphabets, Chinese, Japanese and Korean by syllables or initials, 0 and 1 as separators, ranked by recency | **Better** |
| 26 | Keypad searches contacts and calls; long-press options on results; the result list can be hidden | Keypad results with every number of a contact | Equal |
| 27 | Call buttons per SIM with SIM names; USSD | Call pill with SIM segments; USSD replies in a dialog | **Better** |
| 28 | "Always call through the system phone app" | Not needed: Parley is the phone app | n/a |
| 29 | Edit before calling, copy, delete several call-log entries | The same, with Undo, plus Block, Remind me, Message or call on… | **Better** |
| 30 | Calls with one person within an hour grouped together; "View more" paging; history per contact | Grouped by person and day, every call, or by day; number history; the newest 100 load first | Equal |
| 31 | Voice matches pick the most recently used contact | Keypad ranking by recency and frequency | Equal |

**Calls and notifications**

| # | OpenContacts | Parley | Verdict |
|---|---|---|---|
| 32 | Missed-call notifications, cleared when you open the call log | One per caller with photo, SIM and count, "Why didn't it ring?", optional re-alert; cleared by opening Recents | **Better** |
| 33 | No missed-call notification for unknown callers | Silenced or blocked unknown calls notify at the rule's level (`NotifyLevel`: none, quiet or normal). An unknown call that **rang** and was missed always notifies | **Partial**: not worth a setting (§4) |
| 34 | Call filtering: silence or reject everyone who isn't a contact | Silence unknown callers with schedules, allow rules, Expecting a call, the Night Situation, and a "why" trace | **Better** |

**Look, settings and the rest**

| # | OpenContacts | Parley | Verdict |
|---|---|---|---|
| 35 | Light, dark or system theme | Plus AMOLED and dynamic colour | **Better** |
| 36 | 12- or 24-hour time switch | Follows Android | **Lack (by design)** |
| 37 | Sort by first name | Sort by and Show names as | Equal |
| 38 | Lock to portrait; landscape support | Two panes in landscape and on tablets; no portrait lock (Android has auto-rotate) | **Lack (by design)** |
| 39 | Switch that swaps the call and info buttons, against mis-taps | Tapping a row opens it; Call is a separate button; buttons in the list are optional | Equal (another solution) |
| 40 | Bottom menu for one-handed use, "open on start" | Bottom bar or rail with customisable tabs | Equal |
| 41 | Tab to open on | Open on | Equal |
| 42 | Font size inside the app | Follows Android's font size | **Lack (by design)** |
| 43 | Keyboard resizes the screen | Handled by Compose insets | n/a |
| 44 | Scroll bars turned off to stop mis-taps on call buttons | A–Z index in its own lane, apart from the rows (6.2.2) | Equal |
| 45 | Contact count at the top | Count at the end of the list | Equal |
| 46 | Help, What's new, About | Help & troubleshooting, What's new, Tools | Equal |
| 47 | Crash report you can share | Crash card with no personal data | **Better** |
| 48 | Ask for permissions again; works with none | Permissions page in onboarding; each feature says what it needs | Equal |
| 49 | Translations through Crowdin | English only | **Lack (by design)** |
| 50 | Asks for internet, overlay and "all apps" permissions | None of these (enforced in the build) | **Better** |

**Count:** 23 better, 15 equal, 2 partial, 7 lack (6 of them by design), and 3 that don't apply to a phone app (3, 28, 43).

### 3.2 Logger

| # | Logger | Parley | Verdict |
|---|---|---|---|
| L1 | Calls grouped by day, with a count per day (a setting) | By day, with a day summary on tapping the header | **Partial** → OG-3 |
| L2 | Four ways to group: contact and type, consecutive, contact, none; "grouping with filters" switch | Grouped, every call, or by day | Equal (fewer, clearer) |
| L3 | Details sheet: SIM, account ID, type, duration; quick filter for the number | Number history with notes, ring facts, "Why it rang" | **Better** |
| L4 | Swipe to call, SMS or WhatsApp; add or view the contact | Opt-in swipe actions, Message or call on…, Save after a call | **Better** |
| L5 | Scroll to top, clear filters | Standard | Equal |
| L6 | Filter by one number | Number history; search | Equal |
| L7 | "Unknown contacts only" filter (July 2026) | Unknown chip | Equal |
| L8 | Ten call types, including answered elsewhere, Wi-Fi, voicemail, blocked and rejected | Six type groups (`TypeGroup`); Wi-Fi and HD tags on the row | Equal |
| L9 | Periods: today, yesterday, this or last month, this or last year, all time, **custom from–to** | Today, 7 days, 30 days, this month, 90 days, this year, any. **No custom range** | **Worse** → OG-2 |
| L10 | Minimum and maximum duration (needs a setting first) | Minimum and maximum talk time in the filter, no setting | **Better** |
| L11 | SIM or phone account (needs a setting first; shows raw account IDs) | SIM filter by name | **Better** |
| L12 | Saved filter presets | Saved filter chips | Equal |
| L13 | Counts: made, received, missed, rejected, blocked | Call insights | Equal |
| L14 | Total, average and longest call; **top N longest calls** | Talk time in and out, per week; no list of longest calls | **Partial** → OG-4 (no) |
| L15 | Most called and most received number | Most calls, most talk time | Equal |
| L16 | In/out chart; how often each contact calls | Talk split, top people, per SIM, answer rates, calls you didn't return | **Better** |
| L17 | Total talk time on the list (a setting) | Only in Insights or the day summary | **Partial** → OG-3 |
| L18 | Tracklist: a few people (by name since March 2026) with a 0–100 "interaction score" that decays, days since the last call, average calls per week and month, average and peak duration, calls by weekday | The Circle: natural rhythm, Due/Soon/Fine, "how you two keep in touch", your turn, quieter than usual, Your year; per-contact weekday × hour heatmap and "usually answers after 6 pm" | **Better**. A score was turned down (C1) |
| L19 | Tracking a number that isn't saved | The Circle covers contacts only; Callback watch and To call cover single numbers | **Partial** → OG-5 (no) |
| L20 | Export CSV or JSON (iCal in the code); field descriptions | CSV (formula-safe, optional BOM, notes), JSON, ICS, PDF, print; of a filter or one person | **Better** |
| L21 | File-name format with date tokens | Sanitised names from the subject and date | **Lack** (not wanted) |
| L22 | Confirm before downloading; hide the share button | Share or save through Android | Equal |
| L23 | Import its own CSV into Android's call log | Parley, Logger or any columns; dry run; skips duplicates; never marks calls new | **Better** |
| L24 | "Sync call log contacts": rewrites the names stored in Android's call log from current contacts (`WRITE_CALL_LOG`) | Parley names calls live and never rewrites the stored names. But see OG-1: it never **clears** them either | **Lack** → OG-1 (privacy side) |
| L25 | "Round calls up to whole minutes for billing" (round 3 found it overwrote the real durations) | Plan minutes per SIM with a billing increment, only counted, never written back | **Better** → NX-5 |
| L26 | Onboarding | Set up the basics | Equal |
| L27 | 16 languages | English only | **Lack (by design)** |
| L28 | Dynamic theme | Material 3 Expressive, dynamic colour | Equal |
| L29 | Files kept only in the cache, deleted on exit | Export files deleted once shared; no storage permission | **Better** |
| L30 | Reads names through `READ_CONTACTS` and caches a failed lookup as "no contact" (round 3) | One answer for "whose number is this" (`NumberOwners`) that fails closed | **Better** |

**Count:** 11 better, 11 equal, 4 partial (L1, L14, L17, L19), 1 worse (L9), 2 lack (L21, L24) and 1 lack by design (L27).

**Round-3 bugs in Logger, re-checked in 3.4.2 and `main`:** the CSV comma bug (#52) was fixed in January 2026. Tracking is now by contact name (#55), which follows a renamed contact but merges two people with the same name. The other round-3 findings remain: rounding overwrites the durations, import doesn't skip duplicates, and a failed lookup is cached. None of them affects Parley.

## 4. Gaps: are they worth closing?

Each gap is checked the same way: is it useful to Parley's users, and does it fit the principles (offline, private, no new permission, no new setting, menus of seven items or fewer)?

| ID | Gap | Useful? | Fits? | Effort | Recommendation |
|---|---|---|---|---|---|
| **OG-1** | Archive leaves the person's name in Android's call log | High: it breaks a stated promise | Yes: Parley already holds `WRITE_CALL_LOG` as the phone app | S | **Fix in 7.0** |
| **OG-2** | Custom date range in the Recents filter | Medium: accountants, expense claims, "calls in March", exports | Yes: part of the existing filter sheet | S | **Yes**, 7.0 or 7.1 |
| **OG-3** | Totals for a filtered view | Medium: "how long was I on the phone with the bank this month?" | Yes: one line, no switch | S | **Yes**, with OG-2 |
| OG-4 | List of the longest calls | Low | Yes | S | **No.** Your year and the People card cover reflection. A "longest" list adds a number that invites the wrong comparison |
| OG-5 | Keep in touch with a number that isn't saved | Low | Weak: the Circle reads contacts; unsaved numbers would need another identity | M | **No.** Save the number as a private or temporary contact; Callback watch covers "waiting on a number" |
| OG-6 | Voice actions through a third-party accessibility app | Low–Medium for a few people | No: an outside SDK, an exported receiver that places calls, and a niche companion app | M | **No**; build NX-6 instead |
| OG-7 | No missed-call notification for unknown callers who rang | Low | It would be a new setting | S | **No.** "Silence unknown callers" with its notification level already covers people who don't want unknown calls |
| — | CardDAV, partner API, translations, font, rotation, 12/24 h, file-name tokens, interaction score | — | Ruled out by no internet, the 5.6 decision, English only, following Android's settings, and C1 | — | **No** |

**OG-1. Archive takes the name out of Android's call log.**
- *Problem (verified):* CONTACT_MODEL says an archived contact is "out of every list, search, picker and widget and out of other apps". But `ArchiveStore` never touches Android's call log. When a call is logged, Telecom stores the caller's name in the call's row (`CACHED_NAME`), with the lookup URI, photo and number label next to it. Android doesn't clear those values when the contact goes. So every past call with an archived person still names them to any app holding `READ_CALL_LOG`. Logger is one such app: it lists those names, and since March 2026 it tracks people by them. Parley also writes stored names **into** Android's log on three paths: `CallHistory.undoDelete` and `ProviderRestore` (through `ArchivedCalls.values`), and the CSV import (`CallCsvImport` sets `CACHED_NAME`). A restore on a new phone, or an imported Logger file, can therefore put an archived person's name back. Private contacts are safe: their calls leave Android's log altogether.
- *How:* when a contact is archived (by hand, in bulk or at a Chapter's end), blank `CACHED_NAME`, `CACHED_LOOKUP_URI`, `CACHED_PHOTO_URI`, `CACHED_PHOTO_ID`, `CACHED_NUMBER_LABEL` and `CACHED_NUMBER_TYPE` on that person's rows, matched by every number form `NumberOwners` matches. Do the same in the daily upkeep, for rows written while Parley wasn't running. On the three write paths, write a name only when `NumberOwners` says a visible contact owns the number. Recents doesn't change: it already prefers `archivedName` to the stored name. Unarchive needs nothing: Parley names the calls from the address book again, and Telecom fills in new calls.
- *Reuses:* `NumberOwners`, `ArchiveStore`, `CallLogRepository`, the daily upkeep, `VaultNumberKeys.Prefilter`'s number forms. *Privacy:* removes data and adds none. *Effort:* S. *Risk:* other dialers would show the number instead of the name for those calls, which is exactly what archiving promises. Add a test for each path and a line to the archive tests. Update CONTACT_MODEL ("Archived contacts") and SECURITY_MODEL.

**OG-2. A custom date range.**
- *How:* the filter sheet's period gets **Custom…** (from and to day, with the date picker the editor already uses). Store it as two new optional fields (`fromDay`, `toDay` in epoch days), **not** a new `FilterPeriod` value: `Codecs.stored` ignores unknown keys but not unknown enum values (the FC-4 lesson), so an older Parley reading a saved chip just sees "Any". Export… already exports the filtered view, so "March for my accountant" works with no change there. *Effort:* S. *Risk:* none beyond the codec test.

**OG-3. Totals for a filtered view.**
- *How:* whenever a filter or a saved chip is active, one quiet line under the chips: **"23 calls · 3 h 12 min · 4 missed"**. It is worked out from the rows already loaded, plus `CallLogIndex` for periods longer than the loaded page. No switch: it only appears when you have asked a question by filtering. *Effort:* S. *Privacy:* private calls count only while they may show (`PrivacyView`).

## 5. Beyond both apps

**Principles** (Parley's, applied here):
- **Your phone's record, not a verdict.** Parley states what it saw and how sure it is. It never claims legal weight or a carrier's error.
- **On your request, for one subject.** Nothing runs in the background except what an existing upkeep already runs.
- **No new permission, no settings row, no new notification type.** Each idea is an action, a choice inside an existing sheet, or a line on an existing page.
- **Private contacts and duress.** Each feature works for private contacts while they are visible, never exposes them while hidden or locked, and has a line in the duress test.

### 5.1 Ranked

| Rank | ID | Feature | Value | Effort |
|---|---|---|---|---|
| 1 | NX-1 | A call record you can hand over | High (safety, disputes) | M |
| 2 | NX-2 | Who can see your calls | Medium–High (privacy, every user once) | S |
| 3 | NX-3 | Temporary until the job's done | Medium, frequent | S |
| 4 | NX-4 | In memory | High for a few, at a hard time | M |
| 5 | NX-5 | Talk time per person, rounded for billing | Medium for professionals | S–M |
| 6 | NX-6 | A pinned "Call back" shortcut | Low–Medium (accessibility) | S |
| later | NX-7 | Check a phone bill | Medium for a few | M |

### 5.2 Each feature

**NX-1. A call record you can hand over.**
- *Problem:* someone harassing you, a landlord who "called three times", a company that says it rang you back, a carrier complaint. People photograph their call log, which is incomplete: calls that were blocked or silenced aren't shown, and old calls get trimmed. Logger exports raw rows. Parley's case-file PDF covers organisations, not people or nuisance numbers.
- *How:* in a number's history (and in a case file's Export), Export… gains **Call record (PDF and CSV)**. It covers one number or a case's numbers over a date range (OG-2's picker), oldest first. Each row: date and time with the time zone; direction; answered or not; duration; the SIM; and, for calls that never rang, **what Parley did and why** ("Silenced: unknown caller at night", from the stored screening trace), plus ring facts where they still exist. The header states what the record can and can't show: "Calls this phone saw from 3 Mar to 12 Oct 2026. Calls made while Parley wasn't the phone app aren't included." Every page carries a short **SHA-256 fingerprint** of the CSV, which goes alongside, so anyone can check the two match. Notes appear only when ticked, as in the case-file report. **Keep forever** on a number also keeps its blocked attempts beyond the usual year.
- *Reuses:* `CallExport` (CSV, PDF through `PrintManager`), `CaseReport`'s layout and "include notes" choice, `blocked_calls` with `TraceCodec`, `call_rings`/`RingFacts`, `keep_forever`, `HistoryFilter`. *Privacy:* made only when you ask, for the subject you choose. Temporary files are deleted after sharing. A private contact's record needs the unlock and is refused under duress, with nothing on screen saying why, as other exports are. *Effort:* M. *Risk:* overclaiming. The wording says "a record from your phone" and never "evidence". Blocked attempts are kept for 365 days today (`pruneBlocked`), so say so in the header. Later, an optional signature with the phone's card key (`SignedCards`, Ed25519) would prove the record came from this phone. Leave it out for now: a signature can identify you to whoever you send it to.

**NX-2. Who can see your calls.**
- *Problem:* Logger, like any call-log app, reads every call with every name Android stores. People know which apps can read their contacts (Parley tells them) but rarely which apps can read their calls. Call history shows who you talk to and when, which is often more sensitive.
- *How:* Settings › Privacy › "Who can see your contacts" gets a second section, **"Apps that can read your call history"**, listed the same way. Each app links to its Android permission page. A line explains that names stored with calls are visible too, and that Parley takes them out for archived and private people (OG-1). Same screen, no new row.
- *Reuses:* `ContactsAudit` (made to take the permission as a parameter, `READ_CALL_LOG`), its notes for messaging and phone apps, the launcher `<queries>` (never `QUERY_ALL_PACKAGES`). *Effort:* S. *Risk:* apps without a launcher icon can't be listed; the screen already says so.

**NX-3. Temporary until the job's done.**
- *Problem:* a temporary contact expires a fixed number of days after you save it (OpenContacts: always 30). A plumber whose job runs into a fourth week, or a buyer still haggling over your old sofa, gets the "delete?" question in the middle of the job. When nobody has called for a while, the job is usually over.
- *How:* the temporary duration picker gets one more choice: **"7 days after your last call"** (or 14, or 30). Every connected call, and every chat started from Parley, pushes the end date forward. The contact's page reads "Deletes 7 days after your last call (now 19 Oct)". A temporary contact linked to a case file can instead choose **"When this case is closed"**. Nothing else changes: "Ask before deleting" still asks.
- *Reuses:* `TemporaryChoice`, `TemporaryDue`, the daily upkeep, `CallLogIndex`'s last call per number, `interactions` (chats started from Parley), case-file status. *Storage:* one optional `slideDays` (and `untilCase`) in the temporary entry. That needs a Room migration with a default, and the backup field is ignored by older versions (test both). *Effort:* S. *Risk:* a contact that calls forever never expires, which is the right outcome. Its page shows it plainly.

**NX-4. In memory.**
- *Problem:* Logger's issues #27 and #53 come from people who lost a parent and wanted every call kept. The voicemails matter even more: a carrier deletes them after a few weeks, and a new phone loses them altogether. Parley already stops birthday reminders once there is a date of death (`Occasions`) and can keep a person's calls forever. Nothing yet helps keep the voice.
- *How:* when a date of death is saved, the contact page shows once a quiet card: **"Keep their calls and voicemails?"** **Keep** turns on Keep forever for every number of theirs, and copies each voicemail from them that is still on the phone into Parley's sealed storage. The card then becomes a small page you open yourself: first and last call, how many times you talked, their voicemails to play, and the notes you kept. There are no reminders, no "remembered yearly" unless you choose it, and no notification ever.
- *Reuses:* `LifeEvents.isDeath`, `keep_forever`, `VoicemailRepository` (Parley reads the audio as the default phone app), `DurableFiles` plus sealing with the small-records key, `YourYear`'s counts for one person, the backup's sealed sections. *Privacy:* sealed on the phone and in backups; follows the person's private or archived state; hidden under duress like notes. *Effort:* M. *Risks:* backup size (show it on the Backup screen's "what a backup holds"; voicemails are usually under 1 MB each); voicemails can only be read while Parley is the phone app (say so); tone (needs a careful read under WRITING.md).

**NX-5. Talk time per person, rounded for billing.**
- *Problem:* freelancers, tutors, lawyers and therapists bill phone time. Logger rounds every call up to the minute, and in doing so overwrote its stored data. People end up adding it up by hand.
- *How:* Export… gets **Talk time per person**: one row per person and period (week or month) with the number of calls, the real total, and the total **rounded per call** to 1, 6, 15 or 30 minutes (chosen in the sheet, defaulting to the SIM's plan increment when there is one). "Who" uses the existing `WhoPicker` (a label such as "Clients", the Circle, everyone), and the period uses the filter or OG-2's range. CSV and PDF. Durations are never changed.
- *Reuses:* `PlanMeter`'s rounding, `CallLogIndex`, `Who`/`WhoPicker`, `CallExport`. *Privacy:* on request only; private contacts only while visible. *Effort:* S–M. *Risk:* none to data. Say "estimate; your own billing rules apply".

**NX-6. A pinned "Call back" shortcut.**
- *Problem:* OpenContacts' voice add-on exists because some people can't easily find a row and tap a small button. Its useful part is "redial" and "call back", spoken or tapped from anywhere. Parley shouldn't take on an outside SDK for that.
- *How:* Tools › Calls that work better › **"Call back" shortcut** pins one launcher shortcut (and adds it to the Quick Settings tiles list). It calls the last missed caller, or, with no missed call, the last person you spoke to. It always goes through Confirm before calling, with the name read out, and through the pocket guard. Voice Access ("tap Call back") and switch access reach it like any shortcut. It is pinned rather than static: the launcher already shows Parley's four static shortcuts, the most launchers display.
- *Reuses:* `Shortcuts.pinMenu`, `MissedCalls`, the pocket-dial guard, Confirm before calling, the spoken call status. *Privacy:* the shortcut's label never names anyone. A private caller is called only after the app lock opens. *Effort:* S. *Risk:* an accidental call, covered by the confirmation.

**NX-7. Check a phone bill (later).**
- *Problem:* a bill shows a call this phone never made, or a 3-minute call billed as 9 minutes. Checking line by line takes an evening.
- *How:* Tools › **Check a phone bill**: open the carrier's itemised CSV, or paste the text from its PDF. "Choose columns" (the CSV import's mapper) finds date, number and duration. Parley matches each line with its own record (number forms, within 2 minutes, duration within the SIM's billing increment) and lists only the differences: "on the bill, not seen on this phone"; "billed longer than talked, beyond rounding"; "premium or international". **Add to a case file** turns the result into a complaint with the carrier.
- *Reuses:* `CallCsvImport` with "Choose columns", `PasteParser`, `NumberKeys`, `PlanMeter`, case files and `CaseReport`. *Privacy:* the bill is read in memory and never stored unless added to a case. *Effort:* M. *Risk:* bill formats vary a lot, so ship it only after trying a few real bills (the owner's own). It adds a Tools row but no setting.

## 6. Plan

7.0 "Polish and proof" is being built now. Only OG-1 belongs in it: it is a proof item, because it makes the archive keep its promise. Everything else comes after.

| Release | From this study | Why here |
|---|---|---|
| **7.0** | **OG-1** (archive clears stored names; three write paths name only visible contacts) | A broken privacy promise; S; fits "proof" |
| **7.1** | **OG-2** custom range, **OG-3** totals line, **NX-2** Who can see your calls, **NX-3** temporary until the job's done | All S. They share the filter sheet and the privacy screen. NX-2 explains OG-1 to users |
| **7.2** | **NX-1** call record, **NX-5** talk time per person, **NX-6** Call back shortcut | NX-1 and NX-5 both build on OG-2's range and the Export sheet |
| **7.3** | **NX-4** In memory | Needs a careful read of the wording and a device test of copying voicemail |
| **Later** | **NX-7** Check a phone bill | Only after trying it on real bills |

**Every item** lands with its Tools row or help line, a device-checklist line and a duress-test line (the AUDIT_3 rule), adds no settings row, and keeps every menu at seven items or fewer. The only new choices sit inside existing sheets: the filter sheet, Export…, and the temporary duration picker.

## 7. Decisions for the owner

| # | Question | Recommended answer |
|---|---|---|
| O1 | Fix OG-1 (archived people's names left in Android's call log, and written back by restore, undo and CSV import) in 7.0? | **Yes.** It is a promise Parley already makes, and the fix only removes data |
| O2 | Add a custom date range (OG-2) and a totals line for filtered views (OG-3)? | **Yes, in 7.1.** Store the range as two new fields, never a new enum value. *Accepted as recommended (7.1).* |
| O3 | Show "Apps that can read your call history" next to "Who can see your contacts" (NX-2)? | **Yes, in 7.1.** Same screen, no new row. *Accepted as recommended (7.1).* |
| O4 | Build the call record (NX-1), including blocked and silenced attempts and a fingerprint on every page? | **Yes, in 7.2.** No signature for now; the wording says "a record from your phone", never "evidence" |
| O5 | Should Keep forever on a number also keep its blocked attempts beyond 365 days? | **Yes.** It is what someone collecting a harassment record expects |
| O6 | Offer "N days after your last call" and "When this case is closed" for temporary contacts (NX-3)? | **Yes, in 7.1.** These are choices in the existing picker. *Accepted as recommended (7.1).* |
| O7 | Build In memory (NX-4), including copying voicemails into Parley's sealed storage and backups? | **Yes, in 7.3**, offered once when a date of death is saved, never automatic. Show its size in the backup |
| O8 | Add "Talk time per person" with per-call rounding to Export… (NX-5)? | **Yes, in 7.2** |
| O9 | Add a pinned "Call back" shortcut (NX-6) instead of supporting OpenContacts' voice-command SDK? | **Yes** to the shortcut, always with confirmation; **no** to the SDK |
| O10 | Build Check a phone bill (NX-7)? | **Later**, after trying it on two or three real itemised bills |
| O11 | Adopt any of: CardDAV, a partner-app API, translations, an in-app font size, a portrait lock, a 12/24-hour switch, file-name tokens, an interaction score, keep-in-touch for unsaved numbers, longest-calls lists, a switch for unknown-caller missed-call notifications? Borrow any code (both apps are GPL)? | **No** to all. Each conflicts with a principle or an earlier decision, or adds a setting for little value. Nothing here needs their code |
