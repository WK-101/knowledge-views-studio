# Parley vs. four open-source apps: full-code analysis and roadmap

*24 Sep 2026.*

**What was read.** The full source of each app. Every Kotlin/Java file, manifest, resource and build file was read. Scale:

| App | Lines read (approx.) |
|---|---|
| [ConnectYou](https://github.com/you-apps/ConnectYou) (`com.bnyro.contacts`) | ~14k |
| [OpenContacts](https://gitlab.com/sultanahamer/OpenContacts) | ~12k |
| [Libre Contacts Backup](https://github.com/AshkanRafiee/Libre-Contacts-Backup) | ~11k |
| [Modern-Apps](https://github.com/vayun-mathur/Modern-Apps) `contacts/` (`com.vayunmathur.contacts`), plus its shared `library/` and the dialer parts of `communicate/` | ~9k |

**Other sources.** The issue trackers of all four apps, plus Fossify Phone and Fossify Contacts (the strongest demand signal), GrapheneOS forum threads, and Lemmy.

---

## Implementation status (v2.0)

Every roadmap item below is implemented, except:

- **P3-23 Android Auto:** deferred to the Play release. Android Auto's built-in phone UI already reads the same contacts and call log; a calling-category Car App needs Play review.
- **Animated multi-frame QR transfer:** replaced by an encrypted backup file plus a passcode, because receiving frames would need in-app camera access.

Localisation covers en, de, es, fr, pt-BR, hi, ur and ar (machine-assisted; needs native review).

## 1. One-paragraph verdict

None of the four is a real phone app:

- **ConnectYou:** contacts and SMS. Its dialer is written but switched off.
- **OpenContacts:** a private contact list. It shows caller ID with an on-screen overlay, and the system dialer still handles the call.
- **Libre Contacts Backup:** backup only.
- **vayunmathur Contacts:** contacts only. Its sibling dialer rings silently and has no in-call screen.

So Parley's calling core (in-call UI, multiple calls, audio routing, blocking) is already ahead of all of them.

Where they beat us:
1. **Being a complete system contacts provider.** Other apps can use them to pick contacts, and they handle multi-select.
2. **Backup:** scheduled, rotated, integrity-checked backups; Libre Contacts Backup's are also encrypted.
3. **The private-contacts idea** (OpenContacts).
4. **Editor and detail polish, and motion** (vayunmathur).
5. **App lock and home-screen shortcuts** (ConnectYou).
6. **Localisation** (ConnectYou ships 46 languages).

Every one of them also has data-loss bugs in contact editing or vCard handling. Across user reports, the #1 fear is silently losing contacts. That is our opening: be the app that provably never loses a field.

---

## 2. What each app is, briefly

| | ConnectYou | OpenContacts | Libre Contacts Backup | vayunmathur Contacts | **Parley 1.0** |
|---|---|---|---|---|---|
| Scope | Contacts + SMS (dialer disabled) | Private contacts + caller-ID overlay + call log copy | Backup/restore | Contacts (+ separate dialer app) | **Contacts + dialer + in-call** |
| Stack | Compose M3, Room, WorkManager | Java Views, SugarORM (abandoned), minSdk 16 | Plain Java, framework Views | Compose **M3 Expressive**, Nav3, WorkManager | Compose M3, Room, DataStore |
| Contact store | System, or "In-App" Room DB | Own DB only (vCard text per contact) | Reads/writes system | System (keys on *raw* contacts: duplicates) | System (aggregate contacts) |
| INTERNET | No (strips network state too) | **Yes** (CardDAV) | No | No (contacts module) | **No** (build-enforced) |
| Encryption | ZipCrypto backups (broken), password in prefs | ZipCrypto export, password in plaintext prefs | **AES-256-GCM + PBKDF2 600k**, header authenticated as AAD | None | None yet |
| Cloud-backup leak | **Yes**: `allowBackup=true`, Room DB and prefs go to Google | No | No | Settings only | No |
| Tests | 1 regex test | 2 | 17 instrumented + retention unit tests | Screenshot previews | 32 unit tests |
| Standout | Pinned contact shortcuts, app lock, OTP-copy on SMS, 46 locales | Private phonebook, temporary contacts, recency-ranked search | GFS retention, integrity manifest, restore categories, SIM (ADN) backup | Shared-element morphs, segmented cards, yearless-date picker, messenger actions, full picker intents | Real InCallService, offline blocking, T9, no-internet proof |

### Bugs we must not repeat (found in their code)

Each of these is a test case we should add to our suite:

- **ConnectYou:**
  - Job title is overwritten by company.
  - vCard import **swaps birthday and anniversary**.
  - Geo intent never includes the address.
  - Favourite toggle uses the wrong ID.
  - Every save deletes and re-inserts all data rows. This loses primary flags and structured addresses and marks the rows dirty for sync.
  - Camera photos become ~160 px EXIF thumbnails.
  - Avatar colours change while scrolling.
  - App lock never re-locks, and the contact picker bypasses it.
  - An exported, permission-less receiver can answer or decline calls.
- **OpenContacts:**
  - The editor rebuilds the vCard from the form, **dropping ORG, TITLE, PHOTO, NICKNAME, UID and more on every edit**.
  - Deleting a contact corrupts call-log rows (`setId(-1)`).
  - The missed-call notification toggle is inverted.
  - Encrypted import is detected by the `.zip` file extension, which breaks with the Storage Access Framework.
  - Number matching with `LIKE '%n'` gives false positives.
- **Libre Contacts Backup:**
  - Starred, ringtone and send-to-voicemail are backed up but never restored.
  - Legacy import makes birthdays into anniversaries.
  - Only thumbnails are kept, not full photos.
  - Restore always *adds*, so running it twice duplicates everything.
  - Whole-archive in-memory processing can run out of memory.
  - Retention can rotate out the last good backup after a mass deletion.
- **vayunmathur:**
  - Keys on raw contacts, so a person with Google and device entries shows twice.
  - Stores Base64 full-size photos for every contact in memory.
  - All new date rows share id 0, so picking one date writes to all of them.
  - A number-only contact gets its number saved as its name.
  - Phone formatting defaults to "US".
  - Hourly job rewrites up to 100 calendar events per contact.
  - The "local accounts" it creates are never registered, so they become orphans.

**Parley status:** we already fixed the equivalent editor issue after our own review (we edit one writable raw contact by row ID). The vCard import path still has gaps; see P1-3.

---

## 3. Pain points people actually report, and our answer

Ranked by frequency across trackers and forums (see §7 for sources).

| # | Pain point | Evidence | Parley answer |
|---|---|---|---|
| 1 | **Contacts silently lost, orphaned, or "gone after X"** | ConnectYou #275, #231, #477, #152; Fossify Contacts #83; GrapheneOS d/17306 | **Change journal + 30-day "Recently deleted/changed" with undo**, per-contact provenance (which account, which app last wrote it), a health check for orphaned contacts, and a diff preview before bulk operations |
| 2 | **Where are my contacts stored?** (Device vs Local vs Google vs DAVx⁵) | Fossify #9 (+18); ConnectYou #57, #104, #465; GrapheneOS d/28638 | Default account setting (done). Next: **storage chip on every contact**, verified **"Move to account"**, and a warning when the account can't hold a field |
| 3 | **Duplicates** (WhatsApp/Signal raw contacts, re-imports) | GrapheneOS d/19813, d/3496; OpenContacts #259; Fossify #37 | Duplicate finder and merge (done). Next: **messenger entries shown as badges, never rows**; **import that upserts**, not blind add |
| 4 | **vCard fidelity** (yearless birthdays, labels, photos, primary number, structured address) | Fossify #618, #30 (+19), #85 (+18); OpenContacts #282; LCB #3 | **Lossless round-trip engine plus published round-trip tests**, full-resolution photos, an import report ("3 fields couldn't be mapped"), and a structured CSV export |
| 5 | **Backup without a server** | ConnectYou #37, #92; LCB #1, #2; OpenContacts #256 | **Encrypted scheduled backups to a folder of the user's choice** (for Syncthing or USB), rotation that works, verify-after-write, restore preview. Optional **live mirror .vcf** |
| 6 | **Call recording** | Fossify #17 (+75) | Not possible for a normal app. Say so honestly in-app; possible later research with Shizuku |
| 7 | **Call-handling basics** (ignore call, choose number, missed-call notice, lock-screen answer) | Fossify #65, #102, #83, #165; GrapheneOS d/22756 | Mostly done in 1.0. **Add "Ignore" (silence only)**, a number chooser when a contact has several numbers, and caller cards |
| 8 | **Spam without cloud** (unknown-caller ringtone, "let repeat callers through") | Fossify #31, #191, #889; OpenContacts #43 | Rules exist. **Add "repeat caller within 3 min rings through"**, a distinct ringtone for unknown numbers, and blocklist import |
| 9 | **Search and T9** (Chinese/Pinyin, search notes and addresses, include history) | OpenContacts #202 (33 comments), #251; Fossify #86, #150 | T9 done. **Add global search (notes, org, address, history), Pinyin T9, and recency ranking** |
| 10 | **Groups/labels, birthdays** | ConnectYou #59; OpenContacts #228, #266; GrapheneOS users install a separate birthday app | Label filter exists. **Add label management, group SMS, and a birthday timeline with reminders (no calendar permission)** |

---

## 4. What to adopt: prioritised roadmap

Effort: **S** ≤ 1 day, **M** 2–4 days, **L** ≥ 1 week.

### P1: v1.1 "Complete & safe" (closes the biggest gaps)

1. **Be the system contacts provider (M).**
   - Other apps get Parley's picker: `ACTION_PICK` / `GET_CONTENT` for contact, phone, email and postal, with multi-select returned as ClipData.
   - Also handle `QUICK_CONTACT`, `SHOW_OR_CREATE_CONTACT`, `JOIN_CONTACT`, and `VIEW`/`SEND` of `.vcf` files (import with confirmation).
   - Return **aggregate / Data URIs** (not raw) and grant read permission.
   - Read insert extras as `CharSequence` plus `Insert.DATA`, as the vayunmathur app does.
   - *Why:* without this, Parley isn't a full replacement. Sources: ConnectYou `PickContactActivity`, vayunmathur `buildInsertPrefill`.
2. **Encrypted backup & restore, "Parley Backup" (L).** Based on Libre Contacts Backup's best ideas, with its weaknesses fixed.
   - **Format:** a deterministic ZIP containing:
     - `manifest.json` (SHA-256 per entry, counts, schema version);
     - `contacts.jsonl` (Contact → RawContact → all Data rows, plus starred, ringtone, voicemail flag, aggregation links and groups);
     - full-resolution photos stored once each (deduplicated by hash);
     - an interop `contacts.vcf`;
     - the call log;
     - block rules plus the system block list;
     - speed dial, per-number SIMs and settings.
   - **Crypto (Java crypto library only, testable off-device):**
     - a random data key per archive;
     - streamed, chunked AES-256-GCM, so truncation or reordering is detected;
     - the data key is wrapped by the passphrase (PBKDF2-SHA256, ≥600k iterations) **and** by a printable **recovery key**;
     - scheduled backups use a public-key wrap, so **no password is ever stored on the phone**.
   - **Scheduling:** a background job to a folder chosen through the system file picker.
     - Skip the run if nothing changed.
     - Write to a `.partial` file, verify it, then rename it.
     - **Grandfather-father-son rotation** (from Libre Contacts Backup, with its tests).
     - A **mass-deletion guard**: pause rotation if the contact count drops sharply.
   - **Restore:**
     - preview first (new / enriched / identical / conflicts);
     - modes: merge (default) / add all / replace (after an automatic safety backup);
     - match on account + source ID, then fingerprint;
     - never delete;
     - cancellable, with an undo journal;
     - a full on-screen report.
   - **Check:** the background-job library may add `ACCESS_NETWORK_STATE`. Remove it in the manifest; our CI permission check will enforce this.
3. **Fix `VCardIO` to be lossless (S–M).**
   - Map every ez-vcard property: IM, relation, SIP, all event types, custom labels, primary number, categories → labels, nickname list.
   - Size batches by bytes to avoid binder overflows.
   - Report failed cards instead of swallowing them.
   - Use the multi-vCard URI for export.
   - Add round-trip tests covering every competitor bug listed in §2.
4. **"Recently deleted" + change journal (M).**
   - Snapshot a contact before any delete, merge or edit made in Parley.
   - Keep it for 30 days in Room, with one-tap restore.
   - An undo snackbar after deletes.
5. **Multi-select in Contacts (M).** Long-press starts selection, with select-all and a count in the top bar. Actions: share .vcf, export, add to label, star, delete (with undo), merge selected.
6. **Contacts-tab quick fixes (S each):**
   - Deterministic avatar colours (already hash-based: verify they're keyed on lookup key, not name).
   - Tap the photo to zoom.
   - Linkified notes.
   - Long-press any detail row to copy.
   - Account chip on the detail page.
   - Always one blank phone row in a new contact.
   - Age next to the birthday.
   - Yearless-date picker (stored as `--MM-DD`).

### P2: v1.2 "Delight"

7. **App lock (M).** Biometric or device credential, re-locks after N minutes in the background, optional screenshot blocking (`FLAG_SECURE`), and covers the picker too. **Incoming calls are never locked**: caller names still show.
8. **Private vault (L)**, the OpenContacts concept done properly.
   - Vault contacts live in Room, encrypted with a key held in the phone's secure hardware (Android Keystore).
   - An **HMAC of the E.164 number** allows caller-ID lookup without decrypting everything.
   - Parley's own call screen shows the name natively: no overlay permission, safe on the lock screen.
   - Opt-in **private call history**: copy the call, then delete it from the system log (the dialer role grants `WRITE_CALL_LOG`).
   - **Two keys:** the caller-ID fields work while the phone is locked; notes and history need biometric unlock.
   - Visibility levels per contact: **Device / Vault / Temporary**.
9. **Temporary contacts (S).** An expiry date (plumber, delivery driver). Automatically deleted, optionally with their call history. From OpenContacts.
10. **Messenger actions (M).**
    - Read WhatsApp, Signal and Telegram rows from the Data table.
    - Show "Call with… / Message with…" menus.
    - Place calls through a messenger's calling account when it has one.
    - Optional default per contact ("always Signal for Mum").
    - Messenger raw contacts appear as badges, never as duplicate rows.
11. **Home-screen shortcuts and widget (S–M).**
    - Pinned "Call / Message / Open" shortcuts for a contact, with a number picker; they use the contact's preferred SIM.
    - Dynamic shortcuts for top favourites.
    - A 1×1 direct-dial widget.
12. **Birthdays (M).** Timeline screen plus a local daily notification ("Birthday today – Call / Message"). **No calendar permission.** Optionally, one yearly repeating calendar event (not 100 events).
13. **Call features from the pain list (S each):**
    - "Ignore" (silence without rejecting).
    - Repeat caller rings through.
    - Different ringtone for unknown callers.
    - **Caller card on the incoming screen** showing the contact's pinned note plus "last call 3 days ago · 4 min".
    - "Edit number before call" and "Copy" on Recents rows.
    - Keypad search ranked by recency and including unsaved recent numbers.
14. **Motion & Material 3 Expressive (M).**
    - Switch the theme to `MaterialExpressiveTheme`.
    - **Shared-element morph** from list row → detail header → editor fields, with each field keyed and text scaling rather than reflowing.
    - Segmented grouped-card rows.
    - `animateItem`.
    - FAB that pops away during selection; selection bar that swaps in over the search bar.
    - Predictive-back fades.
    - Adaptive navigation rail on tablets and foldables, plus list–detail on wide screens.
15. **Localisation (M, mechanical).** Move all UI strings to `strings.xml`, per-app language, Weblate. ConnectYou's 46 locales show how much reach this gives.

### P3: v2 "Nobody else does this"

16. **Contacts time machine.** Each backup stores contacts as content-addressed blobs, so backups are incremental and near-free. Restore one contact to a past date ("Mum's number from March"). See a diff between any two backups.
17. **Serverless multi-device sync.** A live mirror of vCard files (single `.vcf`, or one file per contact in `vdir` format) in a Syncthing or Nextcloud folder. Parley notices a newer version from another device and offers a three-way merge keyed on vCard UID.
18. **Personal-CRM layer, offline.**
    - "Last talked 12 days ago", computed from the call log.
    - "Reach out every N weeks" nudges.
    - Relations (spouse, manager).
    - A notes timeline per contact.
    - An "About {name}" header.
19. **Selective, verifiable sharing.** Share a contact by QR with a field picker (done). Add an **encrypted QR** for vault contacts and an **animated multi-frame QR** to move a whole address book between phones with no network. The new phone scans with any QR-scanner app, so Parley never needs the camera permission.
20. **Contact health check.**
    - Flags: title = company, numbers without a country code, the same number in two contacts, a number stored as the name, empty rows, orphaned accounts.
    - One-tap fixes.
    - A stale-contact assistant: "not called in 2 years → mark temporary?".
21. **Privacy dashboard metrics.** "Numbers only in the vault: X. System call-log rows purged: Y. Last verified backup: 3 h ago, encrypted."
22. **Offline caller location.** libphonenumber's offline geocoder shows "Mountain View, CA" for unknown callers. It adds about 1–2 MB, so weigh that first.
23. **Android Auto calling templates**, after checking compatibility with Telecom's car-mode rules.

---

## 5. UI & feature-placement decisions (what goes where)

These are based on what worked or failed in the four apps.

| Decision | Rationale |
|---|---|
| **Tap a row = open, trailing icon = call** (keep, as in Parley 1.0) | OpenContacts and GrapheneOS users report accidental calls from tapping rows or search results. vayunmathur and ConnectYou both open on tap. |
| **Call and message at opposite ends** of contact rows (optional setting) | OpenContacts deliberately swapped their positions to avoid mis-taps. |
| **Every value row is actionable:** tap = primary action, trailing = secondary (SMS / directions), long-press = copy | ConnectYou's detail acts only on the *first* number; vayunmathur's per-row actions and copy-on-long-press are better. |
| **Save in the top bar + discard confirmation** (keep) | ConnectYou's Save is a floating button and back loses edits without warning. |
| **One settings entry per area** ("Backup & restore", "Blocking", "Privacy", "Appearance"), each showing its **current value** as a subtitle | Flat settings lists in OpenContacts and ConnectYou were criticised; Libre Contacts Backup's single hero screen worked well for backup. |
| **Backup screen:** hero card ("Last backup 3 h ago · verified · encrypted" + "Back up now"), then folder / schedule / retention / encryption, then a **list of backups in the folder** and Restore | Libre Contacts Backup's layout, with its gaps fixed (no Toast-only errors, no hunting for the file). |
| **Show a default tab again only after 5 minutes in the background** | OpenContacts: a quick app switch keeps your place. |
| **No permission storms.** Parley's single role prompt is right; never request on every launch | ConnectYou fires every permission request on every activity start; vayunmathur blocks the UI until 4 permissions are granted. |
| **Distinguish loading / no results / nothing yet** in every empty state | vayunmathur's `hasLoadedContacts` pattern. |
| **Deterministic avatar colours, two-letter initials, theme-aware tones** | ConnectYou's colours change while scrolling; vayunmathur's are fixed dark colours and one letter. |

---

## 6. Things we checked in Parley because of this review

Confirmed gaps, now on the roadmap above:

- No pick / get-content / quick-contact / `.vcf` intent handling → P1-1.
- `VCardIO` imports only a subset of fields, silently skips cards that fail, and sizes batches by card count, so batches with photos can overflow the binder limit → P1-3.
- Settings, block rules, speed dial and per-number SIMs aren't in any backup (`allowBackup=false` is correct, but we have no replacement yet) → P1-2.
- Strings are hard-coded in English → P2-15.

Already good in 1.0, and ahead of all four apps:

- A real in-call engine with multiple calls, audio routing and lock-screen handling.
- Offline rule-based blocking with emergency safety.
- Aggregate contacts (not raw).
- The editor writes only to one writable raw contact, by row ID.
- `allowBackup=false`.
- No internet, enforced by the build.
- T9 with accents and Cyrillic/Greek.

---

## 7. Sources

- Code: the four repositories linked at the top, cloned on 24 Sep 2026. File-level references are in the research notes behind this document.
- ConnectYou issues: https://github.com/you-apps/ConnectYou/issues (#275, #231, #477, #152, #57, #104, #470, #59, #37, #92)
- OpenContacts issues: https://gitlab.com/sultanahamer/OpenContacts/-/issues (#95, #202, #6, #269, #256, #259, #282, #228, #266)
- Libre Contacts Backup issues: https://github.com/AshkanRafiee/Libre-Contacts-Backup/issues (#1, #2, #3)
- Modern-Apps issues: https://github.com/vayun-mathur/Modern-Apps/issues (#553, #597, #149, #673, #512)
- Fossify: https://github.com/FossifyOrg/Phone/issues (#17, #48, #83, #150, #165, #477, #31, #102, #65, #191) · https://github.com/FossifyOrg/Contacts/issues (#85, #9, #30, #37, #618)
- GrapheneOS forum: https://discuss.grapheneos.org/d/19813 · /d/22756 · /d/28638 · /d/3496 · /d/18563 · /d/17306
- Competitive note: [Secure-Dialer](https://github.com/Secure-Phone-apps/Secure-Dialer) already markets "zero internet permission". Parley must win on fidelity, safety (undo and backups), polish and call reliability, not on being offline alone.
