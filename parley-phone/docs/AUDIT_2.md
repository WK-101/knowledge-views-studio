# Parley 5.3 deep audit and plan (round 2)

*3 Oct 2026. This is a code-based audit of Parley 5.3.0 (`e085d27`) in five read-only tracks. The full reports, with file:line evidence for every finding, are in `docs/audit/round2/`:*

| Track | Report | Findings |
|---|---|---|
| A. Code quality, shared code, size | [CODE.md](audit/round2/CODE.md) | 28 |
| B. Performance, storage, scalability | [PERFORMANCE.md](audit/round2/PERFORMANCE.md) | 31 |
| C. Security and privacy | [SECURITY.md](audit/round2/SECURITY.md) | 18 |
| D. UI, UX, placement, settings | [UX.md](audit/round2/UX.md) | 35 |
| E. Shortcomings and signature features | [PRODUCT.md](audit/round2/PRODUCT.md) | 15 shortcomings, 13 ideas, 6 cut candidates |

*Round 1 was `AUDIT.md` (3.4). Its plan shipped as 3.4.1–4.0, and most of its findings are verified fixed (SECURITY.md lists which). Rules for every item below: no new permissions, no network, English only, and the design kit.*

## 0. Summary

**Where Parley stands.**
- The architecture boundaries are sound. `core/common` is pure, `telecom` never touches `core/data`, and detekt keeps UI on the design kit.
- The cryptography is correct.
- The call path is lean.
- The information architecture is more deliberate than most dialers'.
- The problems come from growth. Five releases in one round added features faster than the shared plumbing, the stores and the settings tree were adapted to hold them.

**What must be fixed first: correctness and safety.**
1. **Duress mode leaks.** Its promise is "a session's changes don't stick and nothing shows". Three paths break it:
   - Private-name approvals and switches can be changed during a duress session, and they persist (C-01).
   - "Delete all data" during a duress session shows that hidden contacts exist and can destroy them (C-02).
   - A restore writes settings past the duress rules (C-04).
2. **Two crash or failure risks at scale.**
   - The snapshot index is re-parsed in full on every snapshot question: about 225 MB of heap at 5k contacts, so a likely out-of-memory crash (F1).
   - Deleting more than 500 contacts at once fails (F8).
3. **A release bug.** R8 strips `FollowUpWorker`, so follow-ups queued by earlier versions fail (A4).
4. **Two phone-number identity paths.** A hand-written E.164 heuristic and libphonenumber coexist (23 and 13 call sites), and the shared `PhoneIdentity` path is bypassed about 70 times. The same number can match in one feature and not in another (A5).

**Biggest structural wins.**
- **APK size:** compressing the dex takes the APK from about 11.2 to about 6.5 MiB with no feature loss (A1, an owner decision, §6). Dropping two country geocoder files saves another 560 KB (A2).
- **Shared code:** about 2,800–3,000 lines can go by consolidating repeated plumbing:
  - notifications;
  - clipboard;
  - safe activity starts;
  - sealed stores;
  - crypto helpers;
  - `Json` configs;
  - contact rows;
  - settings texts kept in three copies.
- **Storage:**
  - The time-machine format, the undo journal (a full photo per contact per change) and an unbounded call archive grow without limit.
  - Keystore is used per row, about 25k operations a day.
  - The fixes are small but need migrations.

**UX.**
- A basic user gets no help on day one.
- Settings has become a second Tools hub: about 30 of its 95 rows are launchers.
- Block works three different ways, with no Undo and no Unblock.
- Three menus break the ≤ 7 rule; the worst has 14 items.
- Moment-of-use actions are missing: Save after a call, To call from Recents.

**Product.**
- Nothing has been run on a real phone.
- The Tools hub stops at 4.6.
- Private contacts can't be exported in an open format.
- Safe words and helpers don't survive a move to a new phone.

**Signature opportunities:**
- "This number never calls you", a spoof warning for saved organisations.
- **Recall**, one search over everything Parley remembers.
- **Situations**, one tap sets several behaviours together.
- **Case files** for service calls.
- A **family spam shield** over shared labels.

**Your 5.3.1 corrections** (save and share original images everywhere, reordering entries, copy on the contact page, private contacts as relations, search across every field with filters) are being built in parallel. They also close part of Track E #7.

---

## 1. Correctness and safety (fix before anything else)

| # | Finding | Track | Severity | Effort |
|---|---|---|---|---|
| S1 | During a duress session, the private-name switch, the Directory switch and app approvals write straight to storage, so a coercer can approve stalkerware that gets names at the next real unlock. Route all three through the duress-aware settings path and refuse approvals during duress | C-01 | High | M |
| S2 | During a duress session, "Delete all data" reads the real vault: it asks to unlock private contacts and offers "delete without backing up private contacts". Under duress it must see only the concealed view, and it must never destroy hidden data | C-02 | High | M |
| S3 | Restore (`importMap`, `applyPendingRestore`) bypasses the duress rules, and its confirm step accepts the phone's fingerprint. Restores must go through the same duress filter as `update()`, and must ask for the Parley PIN when one is set | C-04, C-12 | Medium | S–M |
| S4 | A private-name approval needs only the phone's unlock, not Parley's lock, and shows a label the requesting app controls. Require the Parley lock, and show the package name and signing-certificate digest | C-05 | Medium | S |
| S5 | A zero-permission app can start `app.parley.SHOW_MISSED` and clear all missed calls. Internal actions must only be honoured from Parley's own PendingIntents (verify the sender, or move the actions to a non-exported alias) | C-06 | Low–Med | S |
| S6 | Private calls stay in the system call log for 2.5–7.5 s, longer if the process dies. Shorten the window: start the sweep at call end in the in-call service, then re-check at the next start | C-07 | Low–Med | M |
| S7 | Lock-screen call screen: the pinned note, "Who is this?" and last call show by default. Hide them while locked unless the user opts in. The widget shows names while Parley is locked: follow Parley's lock state, not only the keyguard. `cache/share` never deletes plaintext vCards or voicemail: sweep it like the camera cache. Device-to-device transfer copies device-protected storage: extend the data-extraction rules | C-08, C-14, C-09, C-11 | Low | S |
| S8 | Signing key: move it offline, use different passwords, delete the plaintext file (owner action) | C-03 | High (operational) | owner |
| R1 | R8 strips `FollowUpWorker` in release: add a keep rule, or cancel the legacy jobs and delete it | A4 | High | S |
| R2 | Deleting more than 500 contacts in one batch is rejected by the provider: send chunks with yield points | F8 | High | S |
| R3 | The time-machine index is re-parsed in full on every query, so likely out of memory at 5k+ contacts. Cleanup decodes every record of every snapshot daily. Use an incremental index (hash → record, deduplicated), keep it in memory with a cap, and clean up by distinct hash | F1, F2 | High | M (format migration) |
| R4 | One identity path for numbers: make `PhoneNumbers` internal, route everything through `PhoneIdentity` (libphonenumber), and add a detekt ban | A5 | High (correctness) | M |
| R5 | Writes and exports run in composition scopes and are cancelled when you leave the screen. Move them to an app-scoped job runner with progress and a notification. Add a cancellation-safe `catching {}`, ban `runCatching` in suspend code, and never show a raw `e.message` | A6, A8 | Medium | M–L (start with exports and imports) |

## 2. Performance, storage and scale

| # | Finding | Fix | Track | Effort |
|---|---|---|---|---|
| P1 | The vault is built at start-up even in the process started for an incoming call, competing with the 3 s screening budget. Private calls are decrypted per row with a Keystore key on every change | Start the vault lazily; never in the screening path. Use a software subkey wrapped by Keystore, as `HistoryCrypto` does | F3 | M |
| P2 | The number-memory rebuild does about 25k Keystore HMAC calls a day and fully decrypts the archive | Wrapped software HMAC key; incremental rebuild | F4 | S–M |
| P3 | The call archive keeps everything with no retention limit and uses OFFSET paging; deleting one person's calls decrypts the whole archive | Default retention (5 years, user-adjustable), keyset paging, per-person queries on the existing index | F5 | S–M |
| P4 | The undo journal stores a full photo per contact; there is no time index and no vacuum, so a bulk edit can add about 1 GB held for 30 days | Store a photo hash and keep photos once; rebuild the table with blobs last; add indexes; incremental vacuum | F6, F24, F25 | M (migration) |
| P5 | Search re-normalises every field of every contact on each keystroke | Prepared search rows built once per change. The 5.3.1 search builder does this | F7 | in 5.3.1 |
| P6 | Restore planning holds two full address books with photos in memory | Stream, and compare by hash | F9 | M |
| P7 | `PeopleIndex` rescans all rows on every change and stays active after first use. Call-log changes start about 10 un-debounced pipelines. The after-call worker holds a 15 MB set. Folder sync runs hourly with no constraints | Use the incremental diff; debounce changes; remove the redundant set; sync only when idle and not on low battery | F10, F12–F14 | S each |
| P8 | Screening runs its lookups in sequence and repeats two of them | Run them in parallel, once each | F15 | S |
| P9 | The private-contact table reads the big detail blob for listings | Rebuild the table with the blob last, or move it to a side table | F11 | M (migration) |
| P10 | List rendering, sorting and photo loading: per-row lambdas, broad state reads, formatting during composition, collator sorting, original photos with no size cap | `items(key, contentType)`, `remember` the formatting, collation keys, cap originals (keep a full copy only below a size, ask above it) | F21–F28 | S–M |

**Target:** 20k contacts, 200k calls, three years of snapshots, with no out-of-memory crash, less than 1 s to first contact list, and less than 150 ms screening. Measure with the existing macrobenchmarks on a device (§7).

## 3. Shared code, less code, smaller app

| # | Action | Saves | Track | Effort |
|---|---|---|---|---|
| L1 | Compressed dex (`useLegacyPackaging = true`), **owner decision §6**. The budget check becomes "download size" | about 4.7 MB of the download | A1 | S |
| L2 | Drop the China and Australia geocoder files (offline area names for those countries) or load them on demand from the Lists companion | 560 KB | A2 | S |
| L3 | Collapse resource names; drop `androidx.fragment`; narrow the ezvcard keep rules; move the six DataStores to SharedPreferences; a `:core:spam` module for the Lists app | about 300 KB | A3, A24, A25 | S–M |
| L4 | A `PrivateNotice` notification helper and request codes from `NotificationRegistry` | about 220 lines | A12 | S |
| L5 | One clipboard helper (sensitive by default, exceptions caught); one safe-start helper; one `Json` config; one `ContactDetails` codec | about 400 lines | A16, A17, A20 | S–M |
| L6 | One source for settings texts (catalog, page and search share it; fixes 21 drifted titles) | about 500 lines | A13 | M |
| L7 | `PersonRow`, `primaryNumber`, `Section` in core/ui; detekt bans raw `ListItem`/`Switch` outside the kit (227 raw `ListItem`s today) | about 650 lines, one density | A14, A15 | M |
| L8 | Generic sealed line store and shared AEAD/HKDF/Keystore helpers, byte-identical formats | about 370 lines, a smaller crypto-review surface | A18, A19 | M |
| L9 | `ContactDetailScreen` dialog state as one sealed type, split into sections; `CallManager` split into collaborators; `ContactsRepository.save` split by kind | maintainability | A9–A11 | M–L |
| L10 | Strings: 742 duplicate values and 54 unused. Merge the duplicates where the meaning is the same; delete the dead ones | maintenance | A21 | S |
| L11 | Kover coverage and tests for `AppTelecomDependencies`, `AppLock`, `CircleRepository` and lists-updater; commit a generated baseline profile | quality, start-up | A26, A27 | M |

## 4. UX: placement, settings and consistency

| # | Change | Track | Effort |
|---|---|---|---|
| U1 | **One Block flow.** Every "Block" does the same thing: Android's list plus Parley's rule when needed, with a confirm, Undo, and an **Unblock** state wherever a blocked number shows | D20 | M |
| U2 | **Save after a call.** The post-call card for unknown numbers offers Save, Add to contact, and Save privately (temporary stays an option) | D1 | S–M |
| U3 | **Menus ≤ 7 items.** Contact ⋮ (14), selection ⋮ (10) and the Recents sheet (about 16) are regrouped under Share…, Privacy… and More…, with the most-used actions first | D19 | M |
| U4 | **"Set up the basics"** at first run: who may ring (the existing screening presets), Simple mode or not, layout. What's new shows once on fresh installs too. Every Settings page gets a folded **Advanced** group, as SETTINGS.md describes | D11 | M |
| U5 | **Settings are preferences only.** Remove the about 30 launcher rows that duplicate Tools; keep one "Tools" row. Reminders moves to the Settings root. The "Call time" category dissolves into Calls › Situations | D5–D7 | M |
| U6 | **Naming:** "Keep full call history" vs "Keep call history" become "Keep Parley's copy of calls" and "Trim Android's call log". Also: Delete automatically (everywhere), Make private, Recently deleted, and Hide private contacts (instead of "discreet mode") | D12, D16 | S |
| U7 | **To call from anywhere:** Recents long-press, contact page and number history | D2 | S |
| U8 | **Undo everywhere** it's missing: Recents sheet delete, block and allow; label delete (with its ringtone, SIM, rhythm, safe word and rules); block rules and templates | D22, E6 | S–M |
| U9 | **One long-press rule** for lists: select | D21 | M |
| U10 | **Tools hub catch-up:** rows for everything since 4.7 (Drive profile, Phone menus, Calling abroad, Call quality, RTT, Shared labels, Parley PIN, Scam check, Voicemail, Speed dial). Remove duplicate rows. A test makes sure every feature has a row | D8, D24, E2 | S–M |
| U11 | **In-call More order:** note, open contact and copy number first, then a headed "Safety" group | D25 | S |
| U12 | **Default-app fallbacks explained:** where a feature needs Parley as the default phone app, say so in place and offer to switch | E3 | S |
| U13 | **Small bugs:** the Select icon, the Markdown export landing, About's licence row, the SETTINGS.md drift | D | S |

**Merges to avoid** (UX.md §3): Favourites, Circle and Labels stay separate. Tools does not move into Settings. The Blocking screen stays. The Calls sub-pages stay. Private and Temporary contacts stay separate variants.

## 5. Shortcomings to close (product)

| # | Gap | Plan |
|---|---|---|
| G1 | **Nothing has been run on a real phone** (§30–34 device steps, benchmarks, instrumented tests) | Owner or tester runs TESTING.md §30–34 on a Pixel and a Samsung (Android 15/16). I prepare a one-page checklist ordered by risk. Emulator runs in CI if the owner adds one |
| G2 | **Getting data out:** private contacts can't be exported in an open format; the Markdown export skips them | An encrypted or plain vCard export that includes private contacts (with an explicit warning), replacing the Markdown export |
| G3 | **Moving phones:** safe words, helpers, expected-call windows and generated ringtones aren't in backups; undo and snapshots are local | Add them to backups (sealed). Undo and snapshots stay local by design, and the backup screen says so |
| G4 | **Tablets and foldables:** a navigation rail only | List-detail layouts for Contacts and Recents |
| G5 | **Sorting and bulk edit:** contacts sort only by name; bulk edit is thin | Sort by recently added, most called, company. Bulk: add or remove label, set ringtone, set SIM, move account |
| G6 | **Scam help misses spoofed saved numbers:** `ScamCheck.offered` excludes saved callers | Covered by signature feature X1 |
| G7 | **Shared labels need Syncthing or Nextcloud for everyone** | A simpler "exchange file" mode (share one encrypted file by any app) as a starter, keeping folder sync for power users |

## 6. Decisions for the owner

| Decision | Recommendation |
|---|---|
| **Compressed dex** (L1): about 6.5 MiB download instead of about 11.2, but Android unpacks the code at install (slightly slower install, a little more storage used) | **Yes.** The download is what F-Droid and sideload users feel; install cost is one-off |
| **Cut or hide** (PRODUCT.md §3): the private-name lookup provider (no app uses it), plan minutes per SIM, most Recents style/layout combinations, generated ringtones (not backed up, silent fallback on some phones), the Markdown export (→ G2), "Introduce myself…" | Hide plan minutes and Introduce myself behind Tools rows, remove the unused provider, reduce Recents to Rich/Simple × Cards on/off, and keep generated ringtones but back them up (G3) |
| **Marital status** (your question) | No separate field. Show "Married to …" / "Partner of …" on the page from relations (spouse, partner, ex), and keep custom fields for anything else. A dedicated field wouldn't sync or be readable by any other app |
| **Signature features** (§8): which to build first | X1 spoof warning, X2 Recall, X3 Situations in the first wave |

**Owner decisions (3 Oct 2026):** compressed dex yes; keep all Recents style combinations; the other cut/hide recommendations, the relations-based relationship status and the first signature wave (X1–X3) are approved.

## 7. The plan

Each phase is one release, built, reviewed and fixed the same way as 5.0.1–5.3. Phase B (public release) stays deferred.

| Release | Theme | Contents |
|---|---|---|
| **5.3.1** | Your corrections | Save and share original images everywhere; reorder entries; copy name and details; private contacts in relations; search across every field and Filters (P5) |
| **5.4** | Safe and sound | S1–S7, R1–R5, P1, P2, P8, U13. Also the device-test checklist (G1) |
| **5.5** | Lighter and faster | L1–L3 (after the owner's decision), L4–L8, L10, P3, P4, P6, P7, P9, P10, L11 |
| **5.6** | Clearer | U1–U12, the cut/hide decisions, G5 |
| **5.7** | Yours to keep | G2, G3, G4, G7, L9 |
| **6.0** | Signature | X1–X5 (§8), then X6+ by value |

## 8. Signature features (built from what Parley already has)

All are offline and need no new permission. Details are in PRODUCT.md §2.

| # | Feature | What it does | Built from | Effort |
|---|---|---|---|---|
| X1 | **"This number never calls you"** | When a saved organisation (bank, clinic, school) calls in, and you have only ever called *them*, the call screen says so and offers Check it's really them and the scam sheet. It catches the most damaging spoofed calls, which every scam detector lets through because the number is saved | Call archive direction, personal reputation, scam sheet, Verify & call back | S |
| X2 | **Recall** | One search over everything Parley remembers: contacts (all fields), notes, promises, call notes, the call archive, deleted contacts, snapshots, messaged numbers and number memory. "Who was the plumber who called in March?" | 5.3.1 search engine, number memory, time machine, archive | M |
| X3 | **Situations** | One tap sets a moment: "Driving", "Meeting", "Night", "Travelling". Each bundles off hours, drive profile, auto-answer, speaker, replies and SIM, and can switch on by Bluetooth device or time. It also replaces several scattered settings | Drive profile, off hours, auto-answer, speaker default, replies, Quick Settings tile | M |
| X4 | **Case files** | For a service call (bank, insurer, council), Parley keeps the menu path, hold time, reference numbers (sealed) and promises per organisation. It shows them before you call and exports them as a PDF for a complaint | Menu memory, hold mode, call notes, promises, PDF export | M |
| X5 | **Family spam shield** | Block and scam verdicts shared within a family label: signed, hashed numbers only, through the existing shared-label folder | Shared labels, signed changes, block rules | M–L |
| X6 | **Dead-number radar** | Flags saved numbers that keep failing ("number not in service" disconnect causes) and offers to fix or archive them | Call facts, health check | S |
| X7 | **SIM that learns** | Suggests the SIM with fewer dropped calls to each number or area | Call quality diary, per-contact SIM | S |
| X8 | **Chapters** | Labels that expire after a period of life (a house move, a hospital stay, a trip) and tidy themselves up | Labels, temporary contacts' expiry | M |
| X9 | **Courtesy hours** | Your preferred calling hours travel in your signed card. Callers see "Ana prefers calls 9–18" before calling | Signed cards, local time | M |
| X10 | **Archive variant** | Hide a contact from lists and other apps but keep naming their calls, without deleting them | Unified contact variants | M |
| X11 | **Vouched introductions** | Introduce two people with a card signed by you, so each knows it's real | My card keys, signed cards | M |
| X12 | **Agenda** | Things to talk about with someone, shown when you call them or they call you | Promises, caller card | S–M |
| X13 | **Rescue call** | A believable incoming call on Parley's real call screen, to leave a situation safely | Call screen, Situations | S–M |

## 9. What not to do

- Don't merge Favourites/Circle/Labels, Tools/Settings, or Private/Temporary (§4).
- Don't add AI or network features that need call audio or internet.
- Don't add settings without removing or folding one. The settings budget test stays.
- Don't change sealed data formats without a migration and a byte-level compatibility test.
