# Parley 6.2.1 deep audit and plan (round 3)

*8 Oct 2026. This is a code-based audit of Parley 6.2.1 (`a67fd3d6`) in five read-only tracks. The full reports, with file:line evidence for every finding, are in `docs/audit/round3/`:*

| Track | Report | Findings |
|---|---|---|
| A. Code quality, shared code, size | [CODE.md](audit/round3/CODE.md) | 11 (CODE-01…11) |
| B. Performance, storage, scalability | [PERFORMANCE.md](audit/round3/PERFORMANCE.md) | 13 (B1…B13), 5 APK levers |
| C. Security and privacy | [SECURITY.md](audit/round3/SECURITY.md) | 12 new (S3-01…12), 3 still open from round 2 |
| D. UI, UX, placement, settings | [UX.md](audit/round3/UX.md) | 25 (U-01…U-25) |
| E. Shortcomings and signature features | [PRODUCT.md](audit/round3/PRODUCT.md) | 13 shortcomings, 11 ideas |

*Round 2 was `AUDIT_2.md` (5.3). Its plan shipped as 5.4–5.7 and most of it is verified fixed (each track lists what). Overlapping findings are merged below into one item with one ID; the "Sources" column lists the track IDs. IDs in this document use two-letter prefixes (SA, PF, LC, UX, GP, SG) so they don't collide with round 2's S/R/P/L/U/G/X. Where I re-read the code myself, the item says **verified**; where I couldn't, it says **not verified**.*

*Rules for every item: no new permissions (22 today), offline, English only, the design kit, menus of 7 or fewer, the settings budget (§4.3 and §8), no NFC or video. Phase B (public release, licence, external audit) stays deferred.*

## 0. Summary

**Where Parley stands.** The foundations held through five feature releases. The weak spots are at the edges of the 6.x features, where each new feature re-did a piece of shared logic by hand and some got it slightly wrong. Nothing has yet been run on a real phone.

**The most important things, in plain words:**

1. **Some private names still leak.** Notifications for blocked or silenced calls show a private contact's name, even in "Hide private contacts", during a duress unlock and on the lock screen. During a duress unlock, the Rescue call screen shows the planned fake call ("21:30 · Mum"). Backups made during a duress unlock include case-file notes. All three break the duress promise. The root cause is that every feature decides "may private data show now?" its own way. There are 5 different rules (SA-1).
2. **Files can be lost in a crash.** About 44 places save a file by "write a copy, then rename". None of them forces the data to disk first. A crash or battery pull at the wrong second can leave an empty file. If that file is a key file, years of call history and notes become unreadable for good. If it is an archived contact, the person is gone everywhere (SA-3).
3. **A stranger's contact card can plant a hidden "saved" caller.** A vCard or QR code can mark itself as archived, starred and allowed to ring. Once imported it is invisible in your lists, but rings through "contacts only" with a trusted-looking name and never gets the scam warning (SA-4). Separately, "always allow if the name contains…" rules trust a name the caller can fake (SA-5).
4. **Large backups can fail or be unrestorable.** Backups hold every contact photo in memory. With a few thousand photos the automatic backup runs out of memory. On bigger phones it writes a backup that the restore then refuses (SA-7).
5. **Spam can ring through on a cold start.** At every start, including the start for a ringing call, Parley unlocks every private contact one by one just to check ringtones. With a few hundred private contacts this can push call screening past its 1.5-second limit, and the call then rings through (SA-8).
6. **"Who owns this number" gives different answers in different places.** The same number can show a name in one notification and a bare number in another. Archived contacts and network names (6.2 and 6.2.1) reached only some of the 12+ places that answer this question (SA-2).
7. **"This number never calls you" switches itself off after the first scam call** it was meant to catch. One incoming call from the spoofed "bank" disarms it for good (SA-6).
8. **Archive surprises people.** The confirmation doesn't say the contact also leaves the Google account and other devices. It shrinks photos over 512 KB to a thumbnail without saying so, has no Undo, and leaves the name in other contacts' relations and in Android's call log (SA-9).
9. **The 6.x features are hard to find.** Situations, Search everything, Case files, Archived, Chapters and the family spam shield have no row in Tools. So "What's new" told updated users almost nothing about 6.0–6.2 (UX-1).
10. **Still unproven on a phone.** No device run has happened, and Android 17 (stable since June) has never been met. Nothing in the device checklist covers 6.x (GP-1).

**What is genuinely good and must be preserved:**
- **Clean layers.** `core/common` has no Android code, `telecom` never touches the data layer, and the design kit is used everywhere. The 6.x screens contain no raw list items, switches, colours or font sizes.
- **Correct cryptography.** The crypto is still correct, and the stores fail closed: an unreadable store refuses changes and never overwrites. Lost keys are set aside, never destroyed. Every round-2 security fix held (duress, approvals, internal actions, widgets, sweeps).
- **Emergency calls are never blocked or delayed** on any path.
- **Shared plumbing from round 2 is in use:** one phone-number path (`PhoneIdentity`, guarded by a detekt rule), `catching {}`, `PrivateNotice`, `Codecs`, `SealedLineStore`.
- **Lean call path and screening.** The lean start-up process is real, screening runs its lookups in parallel, and every call-screen extra has its own timeout and fails open.
- **One menu builder, one pre-call sheet, one Block flow** with Undo and Unblock. Menus are held to 7 by a test.
- **Settings discipline:** a budget test, Advanced folds on every page, and 12 or fewer basic rows per page.
- **Release-quality areas:** the screening engine (with "why it rang" and Test a call), History & undo, snapshots, Situations' restore logic and Recall's privacy gating.

**Already in 6.2.2 (being built now), so not planned again here:**
- the network-name setting, off by default;
- the network name shown under saved names;
- the alphabet index redesign, which closes UX U-09 (the invisible A–Z rail that swallows taps on the Call button);
- possibly folding "Notes on the lock screen" into "Caller on the lock screen" (UX U-06), which keeps the total at 147 settings.

---

## 1. Correctness and safety (fix first)

| # | Finding | Sources | Severity | Effort |
|---|---|---|---|---|
| SA-1 | **One privacy rule.** "May private data show now?" is computed in 26 files with 5 different rules. Three paths got it wrong, all **verified** in code: (a) blocked, silenced and quiet-hours notifications name private contacts in discreet mode, under duress and on the lock screen, with no public version (`BlockingNotifier.kt` uses `e.contactName` directly); (b) the Rescue call screen shows the pending call and last choices during a duress unlock (no `Concealment` check in `RescueCallScreen`/`RescueCalls`), and `rescue_call` stores the name and number in plain text; (c) case files go into backups made during a duress unlock. **Fix now:** route (a) through the missed-call name helper and `PrivateNotice`; hide (b) while hiding and seal its store; strip notes from (c) while hiding. **Then:** one `PrivacyView` snapshot used by every feature, a detekt ban on reading the raw switches elsewhere, and one duress test that runs through notifications, Rescue and every backup part. Also: give the silenced-call notification its lock-screen version | CODE-02, S3-01, S3-04, S3-05, SECURITY nits | High | S (fixes) + M (`PrivacyView`) |
| SA-2 | **One answer to "who owns this number".** The chain contact → private → archived → network name → bare number is hand-written in 12+ places, with different orders and regions. **Verified:** the To call reminder (`ToCallReminders.nameOf`) skips archived contacts, so it shows a bare number where the missed-call notice shows the name. Expected-call hints treat archived contacts as unknown. One ringing call also resolves the same caller 7–9 times, at up to 3 Keystore operations each. **Fix:** a hot fix for the two known sites first, then one `NumberOwners` resolver with a "use" (screen, notification, lock screen, call path) that applies SA-1's privacy rule, plus a 60-second per-call memo and a small HMAC cache | CODE-01, B6, CODE §4 (digit equality) | High | S (hot fix) + M |
| SA-3 | **Durable file writes.** About 44 write-then-rename sites (the tracks counted 30+ and 41; I count 44 `renameTo` in main code), with five different failure behaviours and **no fsync anywhere (verified)**. At risk: the wrapped key files (`history.keys`, `records.keys`), the only copy of an archived contact (written just before the contact is purged from the account), the PIN record, the vault and the time machine. **Fix:** one helper that writes the temporary file, syncs it, renames it, logs once and returns a result. Keep today's file names so readers don't change. Ban `renameTo` elsewhere with detekt. In Archive, sync before purging. *Choice between tracks:* CODE proposes a plain helper; PERFORMANCE proposes `androidx.core.util.AtomicFile`. Use the plain helper: `AtomicFile` keeps its own backup-file scheme, so every reader would have to change too | CODE-04a, B3 | High (rare, irreversible) | S |
| SA-4 | **Imports can't plant hidden or trusted contacts.** **Verified:** a plain (unencrypted) card keeps its "archived" flag (`CardNotes.forImport`), and starred, send-to-voicemail, ringtone and any custom data type are imported as they are. There is no preview. Also, an encrypted vCard is taken as proof that Parley wrote it, although anyone can make one. And the pre-import count reads lines with no size cap, so a crafted file can crash the process that also hosts the call screen. **Fix:** an import preview where archive, private and ringtone requests are listed and unticked; the QR card rule (control flags dropped) for every plain import; an allowlist of data types; keep notes only from your own signed exports; a bounded pre-scan | S3-02, S3-10, S3-12 | Medium | S each |
| SA-5 | **The network's caller name can't open the door.** **Verified:** an "Always allow: name contains…" rule matches the raw name the network sends (`CallPolicy.kt:559-563, 731`), before block rules, spam lists and the shield. Anyone who can set a caller name gets through, and a zero-width character defeats "block: name contains survey". Once answered, the network's name looks like a saved name. **Fix:** name rules can only block (existing allow-by-name rules: decision D6); match and show the cleaned name; show "From the network" on the answered screen and in the call notification; warn in shared templates. Also tie network-name retention (400 days today) to the call-history trim | S3-03, SECURITY privacy posture | Medium | S |
| SA-6 | **"Never calls you" stays armed.** **Verified:** it requires *all* past calls to be outgoing (`NeverCallsYou.kt:93-94`), so one inbound call, which may be the scam itself, disarms it for that line for good. **Fix:** "Was it a scam?" gets "It wasn't them", which marks that call and leaves it out of the history the check reads. Add a per-contact "They never call me" (a choice on the contact, not a setting) that keeps the warning on. In the number's history, say from when Parley can warn | PRODUCT C1 | High | S |
| SA-7 | **Backups that always restore.** **Verified:** the writer keeps every photo in memory until the end (`BackupArchive.kt:230`), and the reader refuses anything over 256 MB in memory (`:148`). About 2–3k photos is enough to hit both. The call-archive part also builds a full list in memory. **Fix:** the writer keeps only hashes and re-reads photos at the end, in hash order; the reader spools photos to a sealed temporary file; the call lines are streamed. Add a test with 2,000 photos under a 128 MB heap. The backup format doesn't change | B2 | High | M |
| SA-8 | **Nothing heavy at start-up in the ringing process.** **Verified:** the ringtone sweep runs at every start with no gate (`ParleyApp.kt:83-86`) and opens every private contact through the Keystore (`ringtonesNow`), even though the ringtone folder is empty for almost everyone. Situations also start WorkManager at every start. **Fix:** wait for full start, return at once when there are no generated ringtones, and read ringtones from the cached summaries. Skip the Situations cancel when nothing is scheduled | B1, B13 | High | S |
| SA-9 | **Archive: honest and reversible.** The confirmation doesn't say the contact leaves the synced account (so Gmail and other devices too). Photos over 512 KB are kept only as a thumbnail (**verified**, `ArchiveStore.kt:48`). That breaks the "photos kept whole" promise. There is no Undo; relation rows Parley wrote on other contacts stay; Android's call log keeps the name. **Fix:** say both facts in the confirmation; keep the original photo in the sealed record; Undo that unarchives into the same account; take back relation mirrors on archive and restore them on unarchive; say "Old calls in Android's call log may still show the name" (or clear those names). Placement and bulk archive are in UX-7 | PRODUCT H2, C2, S3-07, U-13 (part) | High (data) | S–M |
| SA-10 | **No plain-text fallbacks.** When the Keystore fails, the PIN hashes and archived contacts are written in plain text, and archives are never re-sealed later. The rule-pack signing key (`share.key`) and private contacts' call-screen pictures are always plain. **Fix:** never write plain (keep in memory and retry, as case files do); seal `share.key` once; seal private contacts' backgrounds with the caller-ID key; add Archive to the re-sealing list. Also: report a failed write of the "Lock private contacts" flag | S3-08, SECURITY nit | Low–Medium | S–M |
| SA-11 | **The tile long-press can't relay an internal action.** An exported activity trusts a component name from the intent and opens Rescue call through the internal entry. **Fix:** check that the caller is System UI, or route through the public path; add the case to `ExportedComponentsTest` | S3-09 | Low | S |
| SA-12 | **Family shield needs two voices to block.** In Block mode, one member's verdict (or a former key holder's) blocks a number on everyone's phone. Each personal block is also shared without saying so at the moment of blocking. **Fix:** Block mode acts when 2 members agree or the label's anchor says so (Warn stays at 1); the Block question says "Also shared with Family", with a per-block opt-out. No new setting | S3-11 | Low | S |
| SA-13 | **Supply chain and blob binding.** Dependencies are trusted on first use with no signature check, and Material 3 alpha ships. Sealed blobs carry no associated data. **Fix:** PGP trusted keys for the main publishers; leave the alpha when Expressive is stable; bind table, row, kind and generation in the next blob version (with a migration test) | C-13, C-16 (round 2) | Low / Info | M |

**Not planned, by owner decision.** SECURITY S3-06 ("Lock private contacts" can be ended with the phone's own unlock even when a Parley PIN is set). You decided the current behaviour is fine. The residual risk is that someone who knows the phone's code can open private details after you have locked them. That is recorded here so it is a known choice, not an oversight.

**Small items, do with the area they touch:**
- Untick the Blocking part by default when restoring an unsigned backup: it can add allow rules.
- Call notifications: `setLocalOnly`, so a paired watch doesn't mirror full names (**not verified**: depends on Wear behaviour).
- Delete the two unused emergency safeguards.
- `DataWipe` should also remove the set-aside `*.keys.lost-*` files.
- The case-reference row comment says "Parley unlocked", but only the keyguard is checked: fix the comment or the check.

### 1.1 Owner action: the release signing key

You now hold the key and have saved it. SECURITY (C-03) notes that the keystore password and the key password are the same, and I found that `parley-phone/keystore.properties` still exists on this build machine. It is git-ignored and readable only by its owner, but it holds both passwords in plain text and points at a copy of the key in a temporary folder.

**Recommendation:**
1. **Set one new, long, unique password** on your own computer (`keytool -storepasswd`, and `keytool -keypasswd` if the file is JKS). The old one has sat in plain text on a shared build machine, so treat it as seen. Two different passwords add little when both are kept in the same place. Many PKCS12 keystores can't hold two different passwords anyway. What matters is a strong password kept **separately** from the file.
2. **Changing the password does not change the signing certificate.** Updates keep installing over existing copies. After the change, check that the certificate fingerprint is unchanged (`keytool -list -v`, or `apksigner verify --print-certs` on a signed build) and write that fingerprint down.
3. **Keep two offline copies** of the keystore (for example an encrypted USB drive and a second place), with the password in your password manager, not next to the file.
4. **Remove the copies from the build machine:** delete `keystore.properties` and the temporary key file once you confirm your saved copy opens. From then on, the builder makes unsigned or debug-signed builds, and you sign releases yourself (or supply the key only for that build through `PARLEY_KEYSTORE`).
5. **No key rotation is needed** unless you think the key file itself was copied somewhere else. Rotation (a v3 lineage) is extra work, and it matters most at public release (Phase B).

## 2. Performance, storage and scale

| # | Finding | Fix | Sources | Effort |
|---|---|---|---|---|
| PF-1 | **Recall and number memory decode every snapshot version** each time Recall turns on (2–5 s at 10k contacts) and daily. Once retention starts trimming, number memory also decrypts the whole call archive every day. Recall and number memory read 7 of the same sources and filter them for privacy separately | Remember decoded snapshot versions by hash; keep Recall's corpus while its sources are unchanged; make the daily tally retention-aware; one shared, privacy-filtered `KeptSources` corpus for both (about −100 lines) | B4, B8, CODE §4 (`KeptSources`), PERFORMANCE nits | S–M |
| PF-2 | **The phone-number cache thrashes at 10k contacts.** It is cleared all at once at 4,096 entries, so list builds, Recents search and the ring-path "last call" re-parse every number | A real LRU (about 32k entries); compute each contact's and call's number key once at load | B5 | S → M |
| PF-3 | **Every call ends with whole-file rewrites.** Ring facts, call quality and network names re-seal every row (up to 1,000) and rewrite the whole preferences file on each change. Case files, menu memory and To call rewrite whole documents. Android waits for these writes on the main thread when the call screen closes, a known cause of "app not responding" (ANR). The sealed-document stores are also seven copies of one pattern | Per-call facts go into one `call_facts` table in the history database (one row per fact, SQL pruning). Sealed documents use one shared `SealedDocument` store with one file each, written off the main thread with SA-3's helper; case files split per case. File names and backup sections stay the same (about −300 lines) | B7, CODE-03, PERFORMANCE §5 consolidation 1–2 | M (migration) |
| PF-4 | **The cold Contacts list waits for every private contact** to be opened through the Keystore: 1–2.5 s with 500 private contacts | A sealed listing cache checked against the table, opening only changed rows. The alternative (a software key, as for private calls) needs a security sign-off | B9 | M |
| PF-5 | **The start-up profile is the hand-written 5.x one.** 6.x call-path and start-up code runs slower until Android optimises it in the background, which can take days | Add rules for the 6.x packages; generate and commit a profile once on an emulator; add "ring with private contacts" and Recall journeys | B10, PRODUCT R2, round-2 L11 | S |
| PF-6 | **Ring-path extras read whole files.** The family shield parses every shared label's full state on each cold ring. Archived-contact lookup opens every archived card | A small sealed shield index, and an archive index, rewritten on change | B11, B12 | S |
| PF-7 | **51 small preference files.** About 22 are bookkeeping flags, and about 15 are parsed at every cold start | Two files: `local_state` and `call_path_state`, with keys kept and copied once | PERFORMANCE §5 consolidation 3 | S–M |
| PF-8 | **Memory and storage housekeeping** | Trim image caches when the app is hidden, sized to the phone's memory; drop the time-machine cache after maintenance; cap the place-name and Recents line caches; check an original photo's size before reading it into a backup; a total quota for kept originals (round-2 F28); one shared decrypted `contact_meta` flow; skip the after-call history job when the app is already running; cap the messaging store at 2,000 | PERFORMANCE §4, §7, §8, nits | S each |
| PF-9 | **Measurement gate** | Seed 10k contacts, 500 private, 1,000 archived and 50k calls (the benchmark seed is 3,000 today). Targets: cold screening < 400 ms, full Contacts list < 1 s, Recents keystroke < 50 ms, Recall first result < 300 ms, backup with 3k photos under a 256 MB heap, daily maintenance < 15 s CPU | PERFORMANCE §11 | S (needs a device or emulator) |

**Scale target:** 10k contacts, 500 private, 1,000 archived, 50k calls and 5 years, with no out-of-memory crash and with the numbers above. All timings in the tracks are estimates from the code: **not verified on a device**.

## 3. Shared code, less code, smaller app

The code is already fairly tight after round 2. This round's value is consistency more than bytes. The four places where 6.x features drift (who owns a number, privacy, sealed documents, file writes) are SA-1, SA-2, PF-3 and SA-3 above.

| # | Action | Saves | Sources | Effort |
|---|---|---|---|---|
| — | SA-2 one owner resolver | about 150 lines | CODE-01 | M |
| — | SA-1 `PrivacyView` | about 100 lines | CODE-02 | M |
| — | PF-3 one sealed-document store | about 300 lines | CODE-03 | M |
| — | SA-3 one durable-write helper | about 100 lines | CODE-04a | S |
| — | PF-1 one kept-sources corpus | about 100 lines | CODE §4 | S–M |
| LC-1 | **Guard rails and clean-up:** delete about 25 dead functions and 5 unused strings; regenerate the detekt baseline (−94 stale entries); unit-test the 4 custom detekt rules; turn `PrivateMarkTest` into a detekt rule; require a reason on every `@Suppress`; ratchet counts of `vm.c.` and whole-container constructors. **Replace the about 445 code comments that cite plan IDs ("I21", "L6") with a short reason.** They collide across documents, and the house rule is no ticket tags in code | about 250 lines | CODE Phase A, CODE §5, §6 | S |
| LC-2 | **One call-notification template** for `CallNotifier` and `RescueNotifier`. Public versions go through `PrivateNotice` | about 80 lines | CODE-04b | S |
| LC-3 | **Contact fields:** editor drafts use `@Serializable` instead of a hand-written codec. A test sets every field and checks that every codec, the vault and vCard return it, so a forgotten field fails a test | about 110 lines | CODE-06 | M |
| LC-4 | **Split the giant screens:** `ContactEditScreen` (one 699-line function), then `BlockingScreen`, `KeypadTab` and `BackupScreen`, using the 5.7 pattern (one dialog state, sections, actions through the view model) | about 0 net; easier review | CODE-07 | M each |
| LC-5 | **Error handling:** a mechanical switch of `runCatching` to `catching` at the 489 baselined sites (core/data and telecom first); delete the deprecated alias; one scope for call-screen writes | 0 lines, −489 baseline entries | CODE-08 | S–M |
| LC-6 | **Container discipline:** no new class takes the whole `DataContainer`; no coroutine launch in `init`; small UI façades for Cases, Chapters and Situations; rename the file to `DataContainer.kt`. No DI framework | about 50 lines; fewer start-up races | CODE-05 | L, incremental |
| LC-7 | **Tests:** shared test fixtures instead of 20 copies; replace sleep loops; slim the 30-second ringtone test; tests for `SpamListStore`, `PrivateTrash`, `FamilySafetyStore`, `CallRtt`, `StoreSections` and the workers; a per-module coverage floor at today's level | about 200 test lines; suite under 200 s | CODE-09 | S–M |
| LC-8 | **Build:** one shared Android block for the 6 modules; SDK levels in the version catalog | about 90 `.kts` lines | CODE-10 | S |
| LC-9 | **APK size** (6.42 MiB today, growing about 83 KB per feature release): see below | about 0.4–0.5 MB | CODE-11, PERFORMANCE §6 | S–M |

**APK levers (LC-9), safest first:**

| Lever | Saves (download) | Risk |
|---|---|---|
| Pack libphonenumber's metadata into one compressed asset (a supported loader API) | about 165 KB (measured) | Low, S–M |
| Move the last 3 DataStores to SharedPreferences with a one-time migration (drops DataStore and its protobuf) | about 50–90 KB (the tracks differ: CODE says 50, PERFORMANCE 60–90) | Low, M |
| Lines removed by this plan | about 35 KB | — |
| Merge exact duplicate strings, delete dead ones | about 25–40 KB (**not verified**; CODE rates it low value) | Low, S |
| Arm-only ABIs in the release build | about 20 KB | Low, S |
| Pack the geocoder (area names) files the same way | about 136 KB | Medium, M: after the device run |
| *Move the geocoder to the Lists companion* | *about 540 KB* | *Not recommended (decision D8): unsaved numbers would lose area names without the companion* |

**Totals:** about **1,400–1,700 main lines** (about 1%), about 200 test lines and 90 build lines. The download shrinks by about **0.3 MB** with the low-risk levers, and about **0.45 MB** with the geocoder pack (6.42 → about 6.0 MiB). These are estimates; measure on the built APK.

## 4. UX: placement, settings and progressive disclosure

### 4.1 Changes

| # | Change | Sources | Effort |
|---|---|---|---|
| UX-1 | **Tools and What's new catch up.** Add rows for Situations (featured, taking Drive profile's featured slot), Search everything, Case files, Archived, Chapters, Family spam shield and To talk about, each with its `since` tag. Mention Dead-number radar in the Health check summary. The What's new card names the release's top three rows. The catalog test checks a list of *features* (every README "New in" headline has a row), not just screens. The first time a Situation is turned on, offer to add the Situation tile (no permission). Point the Drive profile row at Situations | U-01, PRODUCT D1, PRODUCT nit | S–M |
| UX-2 | **Search everything from Recents** (the question "who called in March?" starts there): the Recents search falls back to Recall as Contacts does, plus a Tools row and a launcher shortcut. No new tab | U-07, PRODUCT D2/G5 | S–M |
| UX-3 | **A Case files list** in Tools (newest activity first, with open promises) and a one-tap status (Open · Waiting for them · Resolved) that the PDF includes | U-12, PRODUCT H3 | S–M |
| UX-4 | **Post-call card hierarchy:** primary Save (New contact · Add to a contact · Privately for 7 days), Remind me and Block; the rest under More. "Was it a scam?" and "Call a saved number" come forward only when the call matched scam signals or claimed an organisation | U-03 | S–M |
| UX-5 | **Recents actions:** Delete (with Undo) in the selection bar; "Remind me to call" at the sheet's top level (Edit before call moves to More); "Why it rang…" only for incoming calls, with its allow and report actions under "Allow, report…"; Add to contact uses PersonSearch | U-04, U-17 (part) | S |
| UX-6 | **Label page:** an "Add people" button and a corrected empty state (it names a menu item that no longer exists); members first, then settings in a fold; the Chapter row goes into the fold; one ringtone entry, not two | U-05, U-10 (part), UX §2.2 | S–M |
| UX-7 | **Contact menu and variants:** "Keep a case file" leaves the top menu for people (stays for companies or contacts that have a case); Archive leaves "Privacy…" and is offered in the Delete confirmation ("Archive instead: keeps naming their calls"); an archived contact opens a read-only page with Unarchive; bulk Archive and an Archive option in the Health check's stale group. Later, one **"Kept as: Visible · Private · Archived"** row on the contact replaces the Privacy… group (decision D9) | U-10 (part), U-13, UX §9, PRODUCT H2 | M |
| UX-8 | **Contacts header and icons:** drop the Labels icon (the chip stays); one lock button with a small menu when both locks apply; fix the icon clashes (Report, Chapter, encrypted QR, Lock private contacts, Name in their language, Add to contact), and a test that flags a repeated icon within one menu | U-08, U-17 | S |
| UX-9 | **First run for someone moving phones:** "From another phone with Parley: restore a backup" first in Coming from…, offered *before* Set up the basics (which a restore would overwrite) | U-21, PRODUCT R8 | S–M |
| UX-10 | **Situations end:** switching one on by hand asks "For 1 hour · Until [end of its window] · Until I turn it off" (a per-use choice, not a setting); a silent ongoing notice while one silences anyone; a roaming trigger for Travelling | PRODUCT H1, G8 | S |
| UX-11 | **One "who may ring" model:** the Quiet nights preset becomes the Night Situation; Off hours stays the engine on Blocking & screening; Drive profile lives only inside Driving; every page a Situation is overriding shows "Set by Meeting · Turn off" (decision D10) | U-02, UX §9 | M–L |
| UX-12 | **Family shield on Blocking & screening:** a read-only "What decides" list (your rules, spam lists, sales lines, family shield) with each one's state | U-11 | S |
| UX-13 | **"Open with them":** To call, To talk about and promises shown as one card on the contact page and caller header, with storage unchanged. On the call screen, show only items added as "something to talk about", so a to-do list in the note isn't read out as an agenda. Lands with SG-5 Next up | U-22, PRODUCT C3 | M |
| UX-14 | **Incoming caller header:** at most 3 secondary lines while ringing, in a fixed order (safety, who, agenda); the rest after answering | UX §7 | M |
| UX-15 | **Small fixes:** Settings and Tools summaries wrap at large fonts; swipe-delete of a private contact gets Undo; Circle settings opens the right group; the editor's 18 "Add" chips are grouped, with "Name in their language" inside Name details; Contacts search says "Also archived: Ana · Show"; Recall results that take an agenda item get a visible ⋮ instead of a hidden long-press; one Share in the selection bar, not two; filter chips separated from navigation chips; a shared `ParleyTag` for the markers (network, usual, private, archived, work) | U-23, U-24, U-25, UX §3.2, §4, §6, §7 | S each (`ParleyTag` M) |
| UX-16 | **Settings tree** (below) | U-14, U-15, U-16, U-18, U-19, U-20 | M |

**In 6.2.2, not planned again:** the A–Z rail (U-09, part of the alphabet index redesign) and, if it lands, the lock-screen notes fold (U-06). If the fold doesn't make 6.2.2, it is the first item of UX-16. It is a real privacy fix: today a person who chooses "Name" only can still get notes on the lock screen through the older switch (**verified**: `AgendaBridge.kt` ORs the two).

### 4.2 Target settings tree

Keys stay the same; only places and pages move. The root goes from 14 rows to 12. "B" means shown, "A" means under Advanced on its page.

1. **Tools** (as today).
2. **Appearance.** B: Theme, Wallpaper colours, Sort by, Show names as. A: Pure black, Density, Avatars, Second line, Prefer nicknames.
3. **Layout & gestures.** B: Navigation bar, Open on, Simple mode. A: Calls layout, Favourites in Contacts, Swipe actions.
4. **Calls.** Default phone app, Confirm before calling, Pocket guard, Voicemail, then five pages:
   - **Answering.** B: Answer by, Unknown ringtone, Caller photo, Quick replies *(from Messaging)*. A: Background, Flip to silence, Auto-answer, Vibration, RTT.
   - **During calls.** B: Speaker, Screen at ear. A: Vibrate during calls, Power button, Note after calls, Peek before calling, Talk-time reminders & limits *(from Situations)*.
   - **Keypad & dialling** *(the Keypad category plus Phone menus)*. B: Tones, Vibration. A: Letters, Speed dial, USSD, Phone menus.
   - **SIMs & carrier** (unchanged).
   - **Situations.** Situations, Drive profile, Rescue call, Helpers. Nothing else.
5. **Blocking & spam** opens the Blocking & screening screen directly. The page's Advanced rows fold into the screen's Advanced. Repeat callers and Expecting a call are greyed out with "Matters once unknown callers are silenced".
6. **Contacts** (unchanged).
7. **Recents & history.** B: Keep Parley's copy of calls, Trim Android's call log, Clear, **Recents view** (one row that opens the same dialog as Recents ⋮; this absorbs Recents tap). A: Numbers kept forever, Import, People card. All Recents style combinations stay (your decision).
8. **Privacy & security** (Caller on the lock screen now also decides notes; network-name setting from 6.2.2).
9. **Backup & sync** (unchanged).
10. **Reminders.**
11. **Notifications & device.**
12. **About**, plus Help & tips (Reset tips moves here).

**Concrete moves:**

| Move | From → to | Settings effect |
|---|---|---|
| Notes on the lock screen | Calls › During calls › Advanced → into Caller on the lock screen ("Name and notes") | −1 (**in 6.2.2** if it lands) |
| Network name setting | new, Privacy & security | +1 (**in 6.2.2**) |
| People card + "who reaches out first" | two switches → one choice (Off · On · On, with who reaches out first), also on the card's ⋮ | −1 |
| CSV byte-order mark | a setting → a tick box in the export sheet | −1 |
| SIM labels | a setting → always shown when two SIMs are active | −1 |
| Quick replies | Messaging → Calls › Answering | 0 (Messaging root row goes) |
| Messaged numbers expiry | Messaging → Tools › Messaged numbers | 0 |
| Keypad category | root → Calls › Keypad & dialling | 0 (root row goes) |
| Phone menus | Calls › Situations → Keypad & dialling | 0 |
| Talk-time reminders & limits | Calls › Situations → During calls › Advanced | 0 |
| Recents layout, style, tap | three places → one "Recents view" row | 0 (the three stay as values) |
| Reset tips | Settings → About › Help & tips | 0 |

### 4.3 The count

| | Total counted today | Real preferences only (UX §3.1) |
|---|---|---|
| 6.2.1 | 147 | about 99 (about 48 are links, actions or info) |
| After 6.2.2 (+network name, −lock-screen notes) | 147 | about 99 |
| After UX-16 (−3) | 144 | about 96 |

The settings count stays under the ceiling either way. Whether to count only real preferences is decision D1.

## 5. Consolidation verdicts

| Pair or group | Verdict | Why |
|---|---|---|
| Situations ↔ Off hours ↔ Quiet nights preset ↔ Drive profile | **Partial merge** (UX-11) | One night concept, not two. Off hours stays the engine; the Drive profile is only "which Bluetooth is my car". Don't merge with Android's Modes: that needs DND access and is ruled out |
| Agenda ↔ promises ↔ To call ↔ case-file promises | **Merge the presentation, keep the storage** (UX-13) | For a person it is one question: "what's open with Ana?". To call stays the Recents strip until Next up replaces it (SG-5); case files stay organisation timelines |
| Next up ↔ the To call strip | **Merge**: Next up replaces the strip | Adding a second strip would be clutter |
| Archive ↔ Private ↔ Temporary | **Keep three variants; one control** (UX-7) | They answer different questions: who can see it, whether it's in my lists, how long it stays. Merging would blur "locked" with "out of the way" |
| Recall ↔ Contacts search | **Keep Recall as a mode of search, make it reachable from every tab's search** (UX-2) | A separate screen or tab would add a place |
| Family shield ↔ your blocks ↔ sales lines ↔ spam lists | **Keep separate engines, one explanation surface** (UX-12) | Where a verdict came from is the trust story |
| Lock now ↔ Lock private contacts | **Merge the buttons** (UX-8) | One intent at the moment of use |
| Hide private contacts ↔ duress | **Keep apart** | A standing posture versus a security mode |
| Lock-screen notes ↔ Caller on the lock screen | **Merge** (in 6.2.2, possibly) | Two switches for one question, and they disagree |
| Blocking & spam page ↔ Blocking & screening screen | **Merge**: the root row opens the screen (UX-16) | Same switches twice |
| Keypad and Messaging root categories | **Dissolve into Calls / Tools** (UX-16) | 5 and 2 settings don't earn a root row |
| Labels header icon ↔ Labels chip | **Remove the icon** | Same destination |
| Ring facts, call quality, network names, number advice ↔ one table | **Merge the storage** (PF-3) | Same shape; removes whole-file rewrites |
| Seven sealed-document stores ↔ one `SealedDocument` | **Merge the code, keep the files** (PF-3) | One place to keep "unreadable means refuse" right |
| Recall corpus ↔ number-memory corpus | **Merge** (PF-1) | Same 7 sources, filtered for privacy twice |
| Callback watch ↔ Expecting a call | **Build one on the other** (SG-2) | Callback watch is an expected window with a by-date |
| Check-in timer ↔ Rescue call | **One screen, two modes** (SG-8) | Same alarm, ringer and screen |
| Favourites / Frequent / Circle / Labels; Tools / Settings; Private / Temporary | **Keep apart** | Round 2's reasons stand. Tools is what makes UX-1 cheap |

## 6. Shortcomings to close

| # | Gap | Plan | Sources |
|---|---|---|---|
| GP-1 | **No device run; Android 17 never met.** The device checklist has no 6.x line | Add about 12 lines for 6.x: Situations on and off by window and car; a rescue call after 15 min in Doze; archive and unarchive with Google; a "never calls you" call; shield verdicts between two phones; Chapter end; network name on a CNAP carrier; dead-number radar. Then run it on a Pixel (Android 17), a Samsung (One UI 8 or 9) and one strict OEM (Xiaomi or Motorola), and record the results in the file. Owner or tester time | PRODUCT R1, H4 |
| GP-2 | **A first crash leaves no trace** | At start, if Android reports a crash or ANR since the last run, one card: "Parley stopped unexpectedly · Save a report" (nothing stored beforehand). Crash capture on by default in debug builds only | PRODUCT C5, R3 |
| GP-3 | **No old backups in tests** | Golden backups written by 5.0, 5.7 and 6.0 builds, restored in a test; an on-device update from 5.7 to the current release with a vault, archive and an active Situation | PRODUCT R4 |
| GP-4 | **Internet-call rows would look like phone calls** (Android 14+ lets apps log them; One UI 9 shows them). "Call back" would dial through the carrier | Classify rows by the account column Parley already reads; show the app's badge; Call back opens that app. Confirm on the device run first (**not verified**: no device) | PRODUCT C4, G6 |
| GP-5 | **Android 17's system contact picker** may replace Parley's picker | Verify on Android 17. Say in Privacy that the system picker never shows private contacts | PRODUCT G7 |
| GP-6 | **No help** | A "Help & troubleshooting" Tools row: about ten task pages that each open the right tool (Test a call, Diagnostics, default app, battery, "my contact vanished after Archive"). No setting | PRODUCT D4, R6 |
| GP-7 | **No birthday or anniversary on the call screen** (iOS 27.2 has it) | One quiet line from the existing dates code, on the lock screen only where notes may show. Lands with Next up | PRODUCT D3, G1 |
| GP-8 | **Accessibility is checked by hand only** | Automated checks in the instrumented smoke suite once a device or emulator runs | PRODUCT R9 |
| GP-9 | **The Lists companion has no tests** and holds the only network code | Unit tests for download, verify and hand-off | PRODUCT R10 |
| GP-10 | **Small honesty gaps** | "Delete all data" says it doesn't reach your own backup folders and exports; "I'm on hold" says what it does, or writes its hold time into the case file (check whether it already does) | SECURITY privacy posture, PRODUCT nit |

## 7. Signature features

All are offline, need no new permission and add no settings row (each is an action, a per-use choice or a line on an existing surface). Details are in PRODUCT.md §4.

| Rank | # | Feature | What it does | Value | Effort | Note |
|---|---|---|---|---|---|---|
| 1 | SG-1 | **Promise a time** | At decline, missed call or after a call: "I'll call you at 15:30 (their time)". Opens the SMS app pre-filled and adds a To call item. If they ring again, the card says what you promised | High, daily | S | |
| 2 | SG-2 | **Fresh-number warning** | When a saved contact calls from a number added or changed recently by something other than you ("added to Mum 3 days ago, from a pasted message"), say so and offer to check on the older number. Each number on the page shows where it came from | High (safety) | S–M | Ranked above Callback watch: it closes the "save our new number" scam, which every saved-name check trusts. With SA-6 it covers both spoofing paths |
| 3 | SG-3 | **Callback watch** | "They'll call me back by Friday": an expected window for that organisation, a To call line if they don't, and "never calls you" quietened for that window | High | S–M | Builds on Expecting a call and SA-6 |
| 4 | SG-4 | **Queue memory** | "Your last 4 calls waited 6–25 min. Shortest before 9:30 on weekdays", from case files' hold times | Medium–High | S | Needs UX-3 (case files list) |
| 5 | SG-5 | **Next up** | The To call strip becomes at most three "now" lines: calls owed and times promised, today's birthdays (GP-7), a person due in their good window, a case past its date, a Chapter ending | High, daily | M | Lands with UX-13. Absorbs X9's local value |
| 6 | SG-6 | **Booking pocket** | Share a booking email or SMS to Parley; the codes go, sealed, into that organisation's case file and show when you call | Medium–High | M | An offline answer to iOS 27 Call Context |
| 7 | SG-7 | **Situations that suggest themselves** | At most one card a week from your own history ("'Car Kit' connects every weekday at 8:10. Use it for Driving?") | Medium | S–M | After UX-10/UX-11, or it will suggest the old model |
| 8 | SG-8 | **Check-in timer** | A rescue-style call at a set time; if it isn't answered, one tap texts or calls your helper. Nothing is sent without a tap | Medium (safety) | S | Only after the Rescue call device results (alarms are inexact in Doze) and SA-1 |
| 9 | SG-9 | **Call round** | Tell eight people one thing; Parley shows who's next and keeps score, never dialling by itself | Medium | M | |
| 10 | SG-10 | **Care circle** | Siblings share "last spoke with Grandma: yesterday" through a shared label | High for some | L | Same adoption barrier as shared labels: after Phase B |
| 11 | SG-11 | **Official lines pack** | Signed lists of numbers banks and agencies publish, through the Lists companion | Medium | L (curation) | Needs a data owner: after Phase B |

**X9 Courtesy hours and X11 Vouched introductions, re-evaluated.** Both only work between people who run Parley and have exchanged signed cards. Before a public release that is nearly nobody.
- **X9: drop as a feature.** Its local value ("usually answers 18–21 their time") goes into Next up and the pre-call peek. After Phase B, consider one optional "best times" field in My card.
- **X11: defer until after Phase B.** Its local half ("pasted from Sam's message, 12 Mar") comes free with the fresh-number warning (SG-2).

## 8. Decisions for the owner

| # | Question | Recommendation |
|---|---|---|
| D1 | **How should the settings budget count?** Today every row that isn't a plain link counts (147). About 48 of them store nothing (screens, one-off actions, info). UX proposes counting only real preferences, capped at 100 (about 99 today), plus the existing ≤ 12 basic rows per page | **Yes**: count real preferences, cap at **100**. Keep 147 as an outer cap on all rows so links don't sprawl. The real burden is the number of choices, and this stops adding a link costing as much as adding a choice |
| D2 | **Should the two small privacy fixes (blocked-call notifications naming private contacts, the Rescue screen under duress) go into 6.2.2?** | **Yes, if 6.2.2 isn't frozen yet**: both are S and break a stated duress promise. Otherwise they are the first items of 6.3 |
| D3 | **Signing key passwords** (§1.1) | One new long password set on your computer; check the certificate fingerprint is unchanged; two offline copies; delete `keystore.properties` and the temporary key from the build machine; you sign releases. No rotation unless you think the key file was copied |
| D4 | **When will the device run happen, and on which phones?** | On the 6.4 build (after the safety and finishing releases), on a Pixel with Android 17, a Samsung with One UI 8 or 9 and one Xiaomi or Motorola. 6.5 starts by fixing what it finds. If a phone is free earlier, run the existing checklist on 6.3 too |
| D5 | **Order of the next signature wave** | SG-1 Promise a time, SG-2 Fresh-number warning, SG-3 Callback watch, SG-4 Queue memory (6.6); then SG-5 Next up and SG-6 Booking pocket (6.7) |
| D6 | **"Always allow if the name contains…" rules** can be faked by callers. What happens to existing ones? | New name rules can only block. Existing allow-by-name rules keep working only when the network verified the call (STIR/SHAKEN "passed"), and Blocking & screening shows a one-line warning on them |
| D7 | **Archive keeps photos whole?** Today photos over 512 KB become a thumbnail | **Yes**: keep the original in the sealed record, as backups do. It matches the "photos kept whole" promise |
| D8 | **Move the geocoder (offline area names, 540 KB) to the Lists companion?** | **No.** Unsaved numbers would lose area names unless the companion is installed. Do the low-risk metadata pack instead, and consider packing the geocoder (−136 KB) after the device run |
| D9 | **"Kept as: Visible · Private · Archived" on the contact**, replacing the Privacy… menu group | **Yes**, in 6.4 with the Archive work (UX-7). It adds no setting: it is a per-contact choice like Ringtone |
| D10 | **One "who may ring" model:** turn the Quiet nights preset into the Night Situation, with a "Set by Night" banner wherever a Situation is in control | **Yes**, in 6.7 after Situations have met a device. It is the one large redesign in this plan |
| D11 | **Case files on by default for organisations?** SECURITY notes that per-organisation opt-in would be more private | **Keep on** (it is disclosed, sealed, capped at 100 cases, and Queue memory needs it). Add "Stop keeping case files for all" to the Case files list |
| D12 | **X9 and X11** | Drop X9 as a feature; defer X11 until after Phase B (§7) |

**Owner decisions (8 Oct 2026):**
- **D1:** the settings limit may be raised as needed.
- **D2:** the two privacy fixes go into 6.2.2: blocked-call notifications and the Rescue call screen under duress.
- **D3:** the key stays where it is. The owner keeps copies and Claude keeps a copy to build signed APKs. The certificate must not change.
- **D4:** the owner runs the device tests.
- **D5:** yes.
- **D6:** yes.
- **D7:** keep archived photos whole, at full size.
- **D8:** keep area-name data in the app.
- **D9:** yes.
- **D10:** yes.
- **D11:** yes.
- **D12:** yes.

Work on 6.3 starts only after the owner confirms 6.2.2.

**Decisions already made, respected throughout:** keep all Recents style combinations; the current "Lock private contacts" behaviour is fine (SECURITY S3-06 not planned); the network name's visibility is optional (a setting, off by default, in 6.2.2); compressed dex (round 2); NFC and video skipped; Phase B deferred; English only.

## 9. The plan

Each release is built, reviewed and fixed the same way as 5.4–6.2.1.

| Release | Theme | Contents (by ID) | Why here |
|---|---|---|---|
| **6.2.2** *(being built)* | Corrections | Network-name setting (off by default); network name under saved names; alphabet index redesign (closes U-09); possibly lock-screen notes folded into Caller on the lock screen (U-06). D2's two fixes if accepted | Already in flight |
| **6.3** | **Safe and whole** | SA-1 (fixes first, then `PrivacyView` and the duress test), SA-2 (hot fix, then the resolver), SA-3, SA-4, SA-5, SA-6, SA-7, SA-8, SA-9, SA-10, SA-11, SA-12; LC-1 (guard rails, dead code, plan-ID comments); GP-2 (crash card); GP-3 (golden backups); the 6.x lines of GP-1 written into the checklist. U-06 here if it missed 6.2.2 | Privacy promises, data loss and spam ringing through come before anything visible. `PrivacyView` and the resolver are the safety fix *and* the shared code, so they go first. The crash card and golden backups must exist before anyone tests on a phone |
| **6.4** | **Finished and findable** | UX-1, UX-2, UX-3, UX-4, UX-5, UX-6, UX-7 (with D9), UX-8, UX-9, UX-10, UX-12, UX-15, UX-16 (settings tree); LC-4 for `ContactEditScreen` and `BlockingScreen` (both are changed by UX-15/UX-16 anyway); GP-6 Help; GP-10. **Then the device run (GP-1, D4) on this build** | Finishes the 6.x features and makes them visible: mostly S items that users feel at once. Testing on a device after this release tests what users will actually get |
| **6.5** | **Proven, fast and lighter** | First: fixes from the device run, GP-4, GP-5, GP-8. Then PF-1 to PF-9 (with the 10k measurement gate), LC-2, LC-3, LC-7, LC-8, LC-9 (low-risk levers), SA-13 (C-13 part), GP-9 | The performance work needs a device to measure, and the storage moves (PF-3, PF-7) are migrations best done once SA-3's durable writes are in place |
| **6.6** | **Daily signature, wave 1** | SG-1, SG-2, SG-3, SG-4 | Small, daily and safety-relevant; each builds on 6.3 (SA-6) and 6.4 (UX-3) |
| **6.7** | **Now and next** | SG-5 with UX-13 and GP-7; SG-6; UX-11 (D10); UX-14; LC-4 for `KeypadTab` and `BackupScreen` | Next up and the "who may ring" model reshape daily surfaces, so they follow the device run and the smaller features |
| **6.8** | **Occasional and safety** | SG-7 (needs UX-11), SG-8 (needs the Rescue device results), SG-9; LC-5, LC-6 finished | Rarer jobs, each depending on something earlier |
| **Later / after Phase B** | Network-effect features | SG-10, SG-11, X11 (X9 dropped); SA-13 (C-16 blob binding, with the next blob version); the geocoder pack if still wanted | They need other Parley users, a data owner, or a format change best bundled with other changes |

**Ongoing in every release:** each new feature lands with its Tools row (so UX-1 can't happen again), its device-checklist line (so GP-1 can't happen again) and its duress test line (so SA-1 can't happen again).

## 10. What not to do

- Don't merge Favourites/Circle/Labels, Tools/Settings, Private/Temporary/Archive, or Recall into a separate tab (§5).
- Don't add AI, call audio or anything online.
- Don't add a settings row without removing or folding one. Signature features are actions and per-use choices.
- Don't change a sealed or stored format without a migration and a byte-level compatibility test (PF-3, PF-7, SA-13).
- Don't adopt a DI framework (CODE-05): the APK cost and churn aren't worth it.
- Don't drop more geocoder countries or move the geocoder out without a decision (D8).
- Don't build X9 Courtesy hours, X11 Vouched introductions, Care circle or the Official lines pack before Phase B.
- Don't reduce the Recents style combinations (owner decision).
- Don't add version-named files other than this one, and don't cite plan IDs in code comments: write the reason instead (LC-1).
- Don't chase competitors' features that need call audio, a cloud or system rights (call screening by voice, hold assist, recording, RCS checks).
