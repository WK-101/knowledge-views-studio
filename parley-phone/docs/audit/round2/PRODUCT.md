# Track E: Product audit of Parley 5.3.0 (HEAD e085d27)

Read-only audit. Sources: README.md (Features and "New in" rows), docs/COMPETITIVE_ANALYSIS_6.md and _7.md (including §11 build status), CONTACT_MODEL.md, SECURITY_MODEL.md, SHARED_LABELS.md, ACCESSIBILITY.md, `core/common/.../ux/Capabilities.kt`, and spot reads of the code. Paths are relative to `parley-phone/`.

Size for context: about 133k lines of main Kotlin (app 59k, core/common 34k, core/data 25k, telecom 11.7k), about 5,500 English strings, 164 entries in `SettingsCatalog`, and 76 lines of instrumented tests.

---

## Summary (≤300 words)

Feature-wise, Parley is broader than any phone or contacts app it compares itself with. Its weak points are **proof, reach and coherence**, not missing features. **Nothing has been run on a real phone:** CA7 §11 says none of the TESTING.md §30–33 device steps has been run, there are 76 lines of androidTest, and the benchmarks have never been run. For a default dialer that is the main risk. Many signature features depend on how the phone maker or carrier behaves (flip, deflect, RTT, call subject, helper conference, generated ringtones, the Bluetooth drive profile). **Discoverability has fallen behind:** the Tools hub catalog stops at 4.6, so Drive profile, Phone menus, the shared family phonebook, duress PIN, the scam sheet, Call quality and calling abroad have no row in the hub. **Variants are not equal where it matters for leaving:** private contacts can't be exported as vCard or CSV, aren't in the Markdown export or shared labels, and keep no edit history. Parley's own notes, Circle and promises leave only in its own backup format. **"Never lose anything" is uneven:** labels (with their ringtone, SIM, rhythm and safe word), block rules and templates are deleted with only a confirmation. Family safe words and helpers are kept on this phone only, so moving to a new phone loses them. Other basics still missing: one search across everything, list-detail layouts on tablets and foldables, sorting other than by name, more bulk edits, VoIP calls in Recents, and any language except English.

The best new features **combine blocks Parley already has**:
- spoofed-bank warnings (from call direction)
- "Recall", one search over everything Parley remembers
- one-tap Situations
- case files for service calls
- a family spam shield over shared labels
- dead-number radar and SIM choice from call quality
- Chapters (labels that expire)
- courtesy hours carried in signed cards
- an Archive variant
- vouched introductions
- a realistic rescue call

Cut or hide: the LOOKUP_PRIVATE_NAME provider, plan minutes, most of the Recents style and layout combinations, generated ringtones, the Markdown export and "Introduce myself".

---

## 1. Shortcomings, ranked by user impact

**Constraints that apply throughout:** no INTERNET, no new permissions (NFC and camera were skipped), English only, and Android's limits on third-party dialers (no call audio, transfer not available on Android 16).

### 1. No proof that the call path works on real phones (critical)
- **Evidence:**
  - CA7 §11: "None of the device steps in TESTING.md §30–33 has been run on a real phone yet."
  - `app/src/androidTest` has 76 lines in total.
  - CA6 P14 and AUDIT.md: the macrobenchmarks have never been run.
  - Telecom has 11.7k main lines and 2.2k test lines, all Robolectric.
- **Why it ranks first:** a dialer that drops a call, fails to ring or mis-routes audio loses the user for good. Every release since 4.7 added features to the call path (speaker by default, proximity after answer, flip to silence, deflect, the scam sheet, RTT, auto-answer, drive profile) with no device run.
- **Fragile features that need a phone-maker and carrier matrix:**
  - Flip to silence: the accelerometer and its timing.
  - **Send to another number:** `Call.deflect` is rarely supported by carriers.
  - RTT and call subject: only on carriers that support them.
  - "Add my helper": depends on carrier conference support.
  - Generated ringtones: depend on Telecom or System UI being able to read Parley's FileProvider URI. ACCESSIBILITY.md admits a fallback to the default tone.
  - Private contacts' ringtone and "send to voicemail": Parley's own ringer has to imitate Telecom's.
  - Drive profile: depends on the Bluetooth address of the car's pairing.
  - The Android 16 default-account redirect: confirmed in code only (CA7 "Confidence: Medium").

### 2. Features are outgrowing how people find them; the hub is out of date
- **Evidence (`Capabilities.kt`):** the newest `since` value in the catalog is "4.6".
- **No hub row at all for:**
  - Drive profile (4.7)
  - Phone menus and menu shortcuts (4.7)
  - Calling abroad (4.7)
  - Call quality (4.7)
  - RTT (4.7)
  - Shared family phonebook (5.0)
  - Parley PIN and duress PIN (5.0)
  - "Is this a scam?" (5.3)
  - Speaker on by default, flip to silence, "Text me your name" (5.3)
  - Custom fields and other calendars (5.3)
- **Why it matters:** the headline features of the last four releases can only be reached through Settings search or one deep page. CA7 §7 already listed Family safety, Drive, Phone menus and Call time as "reachable from only one or two places".
- **Scale:**
  - 164 catalog entries.
  - About 5,500 strings.
  - Five "important people" concepts remain: Favourites, Frequent, Circle, Labels, To call. Helpers and the stars behind "Allow through Do Not Disturb" add to them.
  - Seven reminder kinds, now on one page.
  - Recents alone has 3 styles (Rich, Simple, Cards) × 3 groupings × separate or combined layouts. 5.0.1 had to add a "What do the colours mean?" legend.

### 3. Most of what sets Parley apart needs the default-dialer role, and the fallback is not explained per feature
Without the role, Parley loses:
- the voicemail inbox
- private contacts' ringtone and "send to voicemail" (CONTACT_MODEL "Still different")
- its whole call screen, so menu memory, the scam sheet, hold mode, speaker by default, the helper and "Check it's really them"
- call facts ("why it rang")

Many users will keep the phone maker's dialer, for example for visual voicemail, which CA7 notes some carriers deliver only to that dialer. The "Can't make Parley the default phone app?" guide is about getting the role, not about what still works without it. A user running Parley only as a contacts app won't know which features are dead.

### 4. Private contacts are second-class for getting your data out
- **Evidence:**
  - `BulkAction.SHARE`, `EXPORT`, `COPY_AS_TEXT` and `MERGE` are `deviceOnly` (`people/BulkActions.kt`).
  - `MarkdownExport.kt:57`: "Private (vault) contacts are never exported."
  - Private contacts are not in shared labels.
  - They have no home-screen shortcut and no widget.
  - A label's "Allow through Do Not Disturb" can't include them.
  - They have no version history or edit undo, only a 30-day trash for deletes (CONTACT_MODEL).
- **Effect:** the only ways to move private contacts to another app are Parley's own backup format or "Make visible", which puts them in the address book where every app can read them. For a privacy app, being unable to leave without leaking is a lock-in and trust problem.
- **Missing:** "Export private contacts" to a passphrase-protected vCard, or a plain vCard after a clear warning.

### 5. Parley's own data can't be exported in an open format, and some of it doesn't survive a move
- **Export:**
  - Notes for calls, call notes, Circle moments, promises, rhythms, relation links and yearly dates are written only to the encrypted backup (`StoreSections.kt` `ContactNotesBackup`, `CircleRepository` sections).
  - The Markdown export is one-way, skips private contacts and can't be read back in.
- **Kept on this phone only** (`PersistentStores.kt`, `local(...)` entries), so lost when moving to a new phone:
  - `family_safety`: safe words, helpers and expected-call windows. A safety feature that disappears on a new phone is a real hazard.
  - Generated caller tunes: the backup keeps the ringtone URI but not the file (ACCESSIBILITY.md).
  - The `journal` (30-day undo) and `timemachine` (6-month snapshots).
  - Personal reputation and call quality. These rebuild by design.
- **What users will see:** "Move to a new phone" puts everything back except some of the safety and memory features they chose Parley for.

### 6. Undo is uneven
- Contacts, calls, To call, Circle entries, swipes and saved filters have Undo.
- Deleting a **label** (`LabelScreens.kt:264,541`) is confirm-only. A label carries:
  - a ringtone and vibration
  - a SIM
  - a Circle rhythm
  - a safe word
  - label block rules and off hours
  - call-time limits
  - shared-label state
- Deleting a **block rule** (`RuleEditorScreen.kt:360`) and removing a rule template (`TemplateGallery.kt:115`) are also confirm-only.
- Merging labels and resetting settings have no undo.
- This breaks the "Never lose a contact" promise at its edges, exactly where configuration that took effort to build lives.

### 7. No one search across everything Parley knows
- Each tab searches on its own. The Contacts search (`BroadSearch`) covers fields, notes and handles. The Recents search covers names and numbers.
- Nothing searches call notes, promises, timeline moments, deleted contacts, snapshots, messaged numbers and the call archive together.
- Number memory only answers passively, when a number calls or is typed.
- The building blocks already exist (`PeopleIndex`, the `NumberMemory` index, the `CallHistory` archive). CA6 U11 ("search is tab-specific", Fossify #150) is still open.

### 8. Tablets, foldables and landscape get only a navigation rail
- **Evidence:** `HomeScreen.kt:104` (`screenWidthDp >= 600`) switches to a rail. There is no list-detail layout for Contacts → contact page, Recents → number history, or Settings.
- Wide-screen handling is ad hoc pixel checks (`ContactDetailScreen.kt:1175`, `KeypadTab.kt:567`, `InCallScreen.kt:219`), not window size classes.
- Phones in landscape get a docked keypad beside the list and a smaller contact header; most other screens just stretch.
- Foldables are a growing share of the Android phones whose buyers might want a premium dialer.

### 9. Basic organising tools still missing
- **Contacts sort only by name.** First or last name (`Settings.sortByFirstName`); favourites have Custom, A–Z and Most called. No sort by recently added, last contacted or company.
- **Multi-select** (`SelectionBar.kt`, `BulkActions.kt`) lacks:
  - Remove from label (only possible on the label page)
  - Set ringtone, vibration or SIM for many contacts
  - Move to another account
  - Block every number of the selected contacts
  - Edit a field across many contacts (for example a company or area-code fix outside the health check)
- **Recents multi-select** is limited to block and delete.
- **Call history** has no filter by duration and no filter by label.

### 10. The "Is this a scam?" sheet is shallow, and misses the worst scams
- `ScamCheck.kt` is a static list of six warning signs.
- `offered(...)` requires `!savedCaller`. Bank and "your bank's fraud team" scams spoof a **saved** organisation number, and that is when the sheet and the scam warning are missing.
- Parley already has the data to flag it (point 1 in §2).

### 11. Shared family phonebook: deep engineering, high setup cost
- What each member needs:
  - Syncthing or Nextcloud
  - a shared folder
  - a passphrase for the label
  - Parley itself
  - an invitation that expires after 7 days
- No photos are shared. Changes arrive within about an hour at best (SHARED_LABELS "Limits").
- The design is excellent, but few households have every member running Syncthing. Without an assisted setup (for example a Syncthing checklist and a test file), adoption will be close to zero.

### 12. English only, while the keypad is marketed for 11 scripts
The keypad searches Cyrillic, Greek, Hebrew, Arabic and CJK, but there are no `values-xx` resources (owner decision since 4.6). The users the T9 feature is aimed at get an all-English interface of about 5,500 strings, and the count keeps growing, which makes adding translations later harder.

### 13. Platform gaps that are becoming basic expectations
- VoIP calls (WhatsApp, Signal) in Recents: One UI 9 has them; CA7 §8.7 still says "investigate".
- Android 17 `ACTION_PICK_CONTACTS`: undecided.
- No Wear or Auto app. CallStyle notifications and the drive profile cover part of this.
- Third-party dialers can't transfer calls on Android 16 (E3 dropped).

### 14. Accessibility is checked by hand only
ACCESSIBILITY.md sets good rules, but no automated `AccessibilityChecks` run (instrumented tests aren't in CI), and none of the 4.7 sweep's device steps has been run. Generated ringtones fall back silently to the default tone on some phones.

### 15. Smaller inconsistencies across variants
- Temporary and private contacts never appear in widgets or shortcuts. That is right, but nothing tells the user why a favourite is missing from the widget.
- A label's "Allow through Do Not Disturb" skips private members (the label page shows only a count).
- Tunes, vibration patterns and auto-answer for private contacts work only while Parley is the default dialer.

---

## 2. Signature features (ranked by value ÷ effort)

Each one combines existing blocks. None needs INTERNET, call audio or a new permission.

### 1. "This number never calls you": spoof warning for saved organisations (S, very high value)
- **Problem:** the most damaging phone scam today is "your bank's fraud team" calling from the bank's real, saved number. Parley shows the bank's name, which makes the caller look more trustworthy.
- **How it works offline:**
  - The call archive and personal reputation already know the direction of every past call per line.
  - When an incoming call matches a saved **organisation** contact (or a label such as "Banks" or "Official"), and the history shows only *outgoing* calls to that line (or no incoming calls in N months), the caller card adds a calm line:
    - "You've only ever called this number. Banks rarely call from it. Check it's really them."
  - The scam sheet is then offered for saved callers too (fixing `ScamCheck.offered`).
  - Optional per-organisation setting: "This organisation never calls me."
- **Why competitors can't easily copy it:**
  - Cloud apps identify numbers. They don't remember the user's own call direction over years, beyond Android's roughly 500-row log.
  - The system dialers trust saved contacts.
- **Privacy:** uses only local history, and the line can be hidden on the lock screen.
- **Reuses:** `CallReputation`, the `CallHistory` archive, `VerifyCallBack.organisations`, `ScamCheck`, `CallerCard`, label policies.

### 2. "Recall": one search over everything Parley remembers (M, very high value)
- **Problem:** "What was the plumber's number from last spring?" The contact was deleted, the number was messaged once, and a note mentions it.
- **How it works:** one search box (the Home header, plus a Tools row) over:
  - contacts and their fields (`BroadSearch`)
  - call notes, promises and moments
  - the call archive with the names calls showed back then
  - deleted contacts (journal and private trash after unlock)
  - snapshots
  - messaged numbers
  - QR scans and "Add several numbers" batches

  Results are grouped by person or number, and each says where it was found ("Deleted 12 Mar · in snapshot · note on Ana's page"). One tap restores, calls or saves.
- **Why competitors can't easily copy it:** nobody else keeps 30-day undo, 6-month snapshots and an archive beyond Android's limit on the phone. Google's equivalent depends on reading your mail.
- **Privacy:**
  - Runs over the existing keyed-hash and sealed index.
  - Private sources appear only after unlock, never in discreet mode or a duress session (`NumberMemory.concealNotes` already handles this).
- **Reuses:** the `NumberMemory` index, `PeopleIndex`, `BroadSearch`, `JournalRepository`, `TimeMachine`, `CallHistory`, `MessagedRecord`.

### 3. Situations: one tap sets everything for a moment (M, high value; also simplifies Parley)
- **Problem:** Parley has about 15 separate switches that people really change together:
  - off hours
  - Expecting a call
  - the drive profile
  - speaker on by default
  - flip to silence
  - auto-answer
  - quick replies
  - ring loud
  - call-length limits
  - the screening preset
- **How it works:**
  - Five built-in Situations: Sleep, Driving, Meeting, Travelling, In hospital/caring. Each is a named bundle of existing settings with an optional schedule or trigger (car Bluetooth, an off-hours window, the SIM roaming state).
  - A Quick Settings tile and the Tools hub show the active Situation.
  - "Test a call" previews it: "In Meeting: only Family and repeat callers ring."
- **Why competitors can't easily copy it:** Android modes control notifications only. Parley owns screening, the ringer and call handling, so a Situation can change who rings, how calls are answered and what reply is sent.
- **Privacy:** no location; triggers are Bluetooth, time and roaming, which Parley already uses.
- **Reuses:** `Schedule`, off hours, `DriveProfile`, `AssistedDial` roaming, `AutoAnswer`, `CallComfort`, the blocking presets, the replay in "Test a call". This can replace several settings rows, which helps the settings budget.

### 4. Case files for service calls (M, high value)
- **Problem:** calls to banks, insurers, utilities and airlines: menus, hold queues, reference numbers, "we'll call back in 5 working days", then a dispute months later.
- **How it works:**
  - Mark an organisation contact as a **case**. During its calls, the More sheet adds:
    - "Reference no." (a sealed field, never sent as tones)
    - "Agent name"
    - "They promised…" (becomes a promise with a date and a To call item)
  - Menu memory replays the path and hold mode times the wait.
  - The organisation's page gets a **Case file**: every call with time, duration, hold time, menu path, notes and promises.
  - "Export case file" writes the existing PDF or CSV history export, filtered to that organisation, for a complaint or chargeback.
- **Why competitors can't easily copy it:** Google's Hold for Me helps with one call. Nobody keeps the whole history across calls, offline, with menu paths.
- **Privacy:**
  - Reference numbers are sealed with the small-records key and hidden in duress.
  - The rule that `MenuMemory` never stores PIN-like digits stays.
- **Reuses:** `MenuMemory`, `HoldMode`, call notes, `Promises`, `ToCall`, `CallExport` (PDF, CSV), `CallQualityDiary`.

### 5. Family spam shield: shared verdicts over shared labels (M–L, high value)
- **Problem:** without a network there is no crowd data. But scams target families, and an elderly parent gets the same scam calls a week after their children did.
- **How it works:**
  - A shared label ("Family") can also share **verdicts**: "Block", "Scam", "Safe: the school nurse", or a range ("Silence this range").
  - Verdicts are stored as signed files in the same encrypted folder, keyed by a label-keyed HMAC of the E.164 number.
  - When the parent's phone screens a call, a verdict counts as a spam-list hit, warning only by default: "Sam marked this as a scam 3 days ago."
  - Every verdict keeps its author and Undo, as label changes already do.
- **Why competitors can't easily copy it:**
  - Truecaller's Family Protection needs its servers.
  - Here the crowd is the people you trust, verified by Ed25519 signatures, with no operator in the middle.
- **Privacy:**
  - Numbers are keyed hashes, unreadable without the label key.
  - Opt-in per verdict. Never automatic: "Share this block with Family?"
  - The scheme already handles member removal and new keys.
- **Reuses:** shared-label sync, membership and signatures (`common/sync/shared`), `ListPack` (as signed packs), `CallScreener` precedence, `CallReputation`, the "why" trace.

### 6. Dead-number radar (S, medium-high value)
- **Problem:** contacts with numbers that no longer work are found only at the worst moment.
- **How it works:**
  - Outgoing calls to a contact's number that fail fast with an error, or never connect across several attempts on different days, mark the number as "probably out of service".
  - The health check and the contact page then offer:
    - "Ask Sam" (a relation or a shared-label member)
    - "Check their card" (a signed-card update)
    - "Keep it, mark it old"
- **Fragility:** carrier disconnect reasons vary, so it relies on repeated patterns, not one code.
- **Why competitors can't easily copy it:** needs Parley's call-quality and failure facts per line, kept over time.
- **Privacy:** local only.
- **Reuses:** `CallFailure`/`CallDrop`, `CallQualityDiary`, `HealthScanner`, `CardUpdates`, `RelationLinks`.

### 7. SIM choice that learns from call quality (S, medium value)
- **Problem:** the Call insights Quality card already says "Calls with Mum drop on SIM 2", but the call pill still puts SIM 2 first.
- **How it works:** once a pattern reaches `CallQualityDiary`'s threshold, the pill shows the better SIM in bold for that person, with a one-tap "Use SIM 1 for Mum". It never switches on its own.
- **Why competitors can't easily copy it:** they have neither the quality diary nor a per-contact SIM model.
- **Reuses:** `CallQualityDiary` (`TRY_OTHER_SIM`), `CallPill`, the per-contact SIM, label policies.

### 8. Chapters: labels that expire, for a period of life (M, medium-high value)
- **Problem:** a house move, a wedding, a hospital stay or a renovation brings 20 one-off contacts (removals company, surgeon's secretary, tiler). People want them to ring through during that time and disappear afterwards.
- **How it works:**
  - A Chapter is a label with an end date. Contacts saved into it become temporary (private or visible).
  - Optional extras:
    - "let unknown callers ring" during the Chapter (Expecting a call for weeks, not hours)
    - expected calls taken from the Chapter's notes
    - a city for "Who's in…"
  - At the end, one review screen offers Keep, Archive or Delete for each contact, with one Undo for the batch.
- **Why competitors can't easily copy it:** no other app has temporary contacts or expected-call windows.
- **Privacy:** private by default; nothing lasts longer than it needs to.
- **Reuses:** `TemporaryExpiry`/`TemporaryDue`, labels and `LabelPolicy`, `ExpectedCalls`, Expecting a call, `TripMatch`, the "Add several numbers" batches with their undo.

### 9. Courtesy hours carried in the signed card (M, medium value)
- **Problem:** calling someone at the wrong time of day, especially in another time zone.
- **How it works:**
  - My card gets an optional "Best times to call me" and quiet hours, with a time zone, signed.
  - Contacts linked to your card see it in Parley before they dial:
    - "Ana's quiet hours (23:10 her time)"
    - Call anyway / Remind me at 8:00 (To call)
  - Their GoodTime suggestion uses it.
  - Your own off-hours rules can fill in the card.
- **Why competitors can't easily copy it:** needs Parley's signed cards that update themselves between Parley users. iOS and Google have nothing like it without their servers.
- **Privacy:** published only if you choose; it reaches people only through cards you shared (the ShareLedger lists who has them).
- **Reuses:** `SignedCards`, `CardUpdates`, `ShareLedger`, `GoodTime`, `ToCall`, off hours.

### 10. An Archive variant: hide without losing (M, medium value)
- **Problem:** address books grow to over 1,000 people. Deleting feels risky and keeping everyone clutters lists and T9.
- **How it works:**
  - A third storage variant (CONTACT_MODEL already says "future variants fit the same shape").
  - Archived contacts leave lists, T9 and widgets, but still name an incoming call ("Archived: Ben, old landlord").
  - "Spring clean" walks through stale contacts from the health check (never called, nothing logged in 2 years) as Keep / Archive / Delete cards.
- **Why competitors can't easily copy it:** needs Parley's variant model and caller ID that works outside the address book.
- **Privacy:** archived contacts can live in the vault, which also removes them from other apps.
- **Reuses:** `ContactVariants`, `VaultRepository` caller-ID copy, `HealthScanner` stale detection, number memory, journal undo.

### 11. Vouched introductions (M, medium value)
- **Problem:** "Here's my electrician's number." A forwarded number arrives with no proof of where it came from, and the electrician calls later as an unknown number.
- **How it works:**
  - Sharing a contact by QR or file from Parley can be **signed by your My card key**: "Introduced by Sam".
  - On the receiving Parley, the contact page and caller card show "Introduced by Sam ✓ (signature checked)".
  - The introduction feeds "Where did you meet?" and Provenance.
  - A Recall search for "Sam" also finds the people Sam introduced.
- **Why competitors can't easily copy it:** needs signing identities between users; Parley already has them.
- **Privacy:** the person introducing chooses which fields to share (existing field choice), and the vouch is visible only to the recipient.
- **Reuses:** `SignedCards`/`Ed25519`, `Handshake`, `ShareLedger`, `Provenance`, the QR share.

### 12. Agenda: things to talk about, shown when you call (S–M, medium value; extends promises)
- **Problem:** "I meant to ask Mum about the insurance." The thought comes up in another app, hours before the call.
- **How it works:**
  - Share any text to Parley → "Add to talk about with…", or type it on any page.
  - Items show on the outgoing and incoming caller card and in the pre-call peek.
  - Tick items during the call. What's left after hanging up offers To call or Keep.
- **Why competitors can't easily copy it:** Magic Cue and Call Context guess from mail and messages; this is curated by the user and works offline.
- **Privacy:** sealed like promises and hidden in duress.
- **Reuses:** `Promises`, `CallerCard`, the pre-call peek, `ToCall`, the share target.

### 13. Rescue call: a believable incoming call when you need to leave (S–M, medium value; personal safety)
- **Problem:** a bad date, an unsafe situation, a salesperson at the door.
- **How it works:**
  - From a Quick Settings tile or a long-press shortcut: "Call me in 1, 3 or 5 minutes as Mum".
  - Parley's *real* call screen (InCallScreen composables, the ringer, the frame, the caller card) shows a ringing call. Answering shows a running call screen with a timer.
  - Nothing is written to the system call log, and nothing is sent.
- **Why competitors can't easily copy it:** separate fake-call apps look fake. Parley *is* the call screen, so the fake looks exactly like a real call.
- **Privacy:** no record kept. Duress-aware: available from the lock screen tile.
- **Risk:** it must never be confusable with a real emergency call. Mark it in Recents only if the user asks.
- **Reuses:** telecom UI, `CallRinger`, `CallerCard`, the Quick Settings tile pattern, `ParleyMotion`.

---

## 3. Cut or hide candidates

1. **Private-name lookup provider for approved apps** (`LOOKUP_PRIVATE_NAME`, `content://…privatenames/lookup/`).
   - No published app declares the permission, so nothing can use it.
   - The Directory provider (I7) covers the real need: other dialers showing private names.
   - It is an extra exported IPC surface with its own approval flow, rate limit and log, all in audit scope (CA7 B9).
   - **Cut**, or leave it in the code with no UI.
2. **Plan minutes per SIM with billing increments and an 80 % warning.**
   - Niche in 2026, when most plans are unlimited.
   - It adds a screen, settings and an Insights card. Allowances already cover supervision.
   - **Hide** it under Call time › Advanced, or cut it.
3. **The Recents style and layout combinations.**
   - Rich, Simple and Cards × grouped, every call or by day × separate or combined (docked keypad), plus a legend screen.
   - 5.0.1 had to add "What do the colours mean?", a sign that the default style needs explaining.
   - **Keep two styles** (Cards and Simple) and one grouping toggle. Fold Rich's badges into Cards.
4. **Generated ringtones from a name** ("Make a ringtone for Ana").
   - Not carried by backups, since the file isn't backed up.
   - Depends on Telecom or System UI being able to read Parley's FileProvider, with a silent fallback to the default tone.
   - Rarely used. Per-person vibration patterns cover "know who's calling without looking" more reliably.
   - **Hide** behind the ringtone picker, or cut.
5. **Markdown notes export for Obsidian.**
   - One-way, device contacts only, and it keeps its own folder state.
   - **Replace** it with the open export from shortcoming 5 (JSON plus vCard plus Markdown, private contacts included after a warning).
6. **"Introduce myself…"** (a prefilled chat per person, one at a time, from multi-select).
   - Niche, and it adds an item to multi-select and to the "Add several numbers" result screen.
   - **Hide** it behind "Send my details".

Softer candidates: "Who's in…" could become only a search operator inside Recall (§2.2) rather than a chip, and picture-in-picture during calls overlaps the CallStyle notification.
