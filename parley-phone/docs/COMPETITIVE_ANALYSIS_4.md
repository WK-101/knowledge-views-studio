# Parley 3.0 vs. seven more apps: bugs first, then what to build

*24 Sep 2026. Round 4. Earlier rounds: [1](COMPETITIVE_ANALYSIS.md), [2](COMPETITIVE_ANALYSIS_2.md), [3](COMPETITIVE_ANALYSIS_3.md).*

**What was read.** The full source of each project, plus its issue tracker and any public discussion that existed.

| Project | Source | Lines read | What it is |
|---|---|---|---|
| **Fossify Phone** `org.fossify.phone` | [FossifyOrg/Phone](https://github.com/FossifyOrg/Phone) v1.11.1 + phone parts of Commons | ~12.5k, ~150 issues | The most widely used FOSS dialer |
| **Amadz** `com.talsk.amadz` | [msusman1/Amadz](https://github.com/msusman1/Amadz) v1.1.5 | ~7.3k | Early Compose dialer |
| **ChatLaunch** `dev.theolm.wwc` | [theolm/WhatsAppNoContact](https://github.com/theolm/WhatsAppNoContact) v0.21.0 | ~3.2k | "WhatsApp without saving the contact" |
| **contacts-android** (library) | [vestrel00/contacts-android](https://github.com/vestrel00/contacts-android) 0.5.0 | core + 1,391-line provider-quirks notes | Kotlin library for the Contacts Provider |
| **Goodwy Contacts** (Right / AlRight Contacts) | [Goodwy/Contacts](https://github.com/Goodwy/Contacts) 8.2.3 + Goodwy Commons | ~18k | Fossify Contacts restyled to look like iOS, with a paid Pro tier |
| **Alternate** `com.lulu786.Alternate` (IzzyOnDroid) | [BioHazard786/Alternate](https://github.com/BioHazard786/Alternate) v2.4.7 | ~11k | A private phonebook for caller ID, kept outside the system contacts |
| **WA Contact Adder** `com.aj.wa.contact.adder` | [amr-jawwad/whatsapp_contact_adder](https://github.com/amr-jawwad/whatsapp_contact_adder) v1.0.1 | ~0.9k | A contacts list that opens WhatsApp chats, so WhatsApp can run without contacts permission |

---

## 1. Verdict

1. **The most valuable result is a list of Parley bugs, several of which can lose user data.** Parley 3.0 is still far ahead of every app in this round on features. But the contacts-android provider notes, together with WA Contact Adder, Alternate and Goodwy, uncovered **3 high-risk data-loss bugs and about 12 medium-risk ones** in Parley's own code. They come first (§2), before any new feature.
2. **Numbers are matched on only the last 9 digits.** Three reports found this separately. It is used for the vault, repeat-caller counts, verdicts, contact-page duplicate removal and the per-contact call history. Two different numbers from different countries that share their last 9 digits are treated as the same number. The fix is to match on full E.164 everywhere, and use the 9-digit suffix only as a fallback or a pre-filter.
3. **Real feature gaps:**
   - **Voicemail inbox** (Fossify's #2 request).
   - **"Message on…" for saved and private contacts.** Today it only works for numbers you haven't saved.
   - **Messenger usernames** (Matrix, Threema, Telegram…) are stored but invisible.
   - **A "Me" card.**
   - **Setting the default number.**
   - **Missed-call notification details:** photo, SIM, and an optional re-alert.
   - **A post-call "Block / Save" card** for unknown numbers.
   - **Localisation.** Fossify ships 82 languages; Parley's UI is English and the text is hard-coded.
4. **Polish.** Goodwy shows that grouped cards, a collapsing contact header and labelled action tiles are most of what makes a contacts app feel premium. Parley should adopt the pattern in Material 3 Expressive form, not copy iOS.
5. **The contacts-android library itself: don't adopt it.**
   - Licence: Apache-2.0, which would be compatible.
   - It uses typed entities, not lossless generic rows, so it would break Parley's backup, time machine and sync.
   - It is only published on JitPack and is pre-1.0.
   - Instead, port its fixes and keep its quirks notes as a reference (§4.4).

---

## 2. Parley bugs found this round (fix first)

Effort: S = about a day, M = a few days.

### High: can lose or expose user data

| # | Bug | Where | Fix |
|---|---|---|---|
| F1 | **Folder sync removes contacts from system groups**, including "My Contacts", the favourites group and Family, Friends and Coworkers. A remote edit rebuilds group memberships from the vCard, which only carries user labels. On Google accounts, affected contacts drop out of "My Contacts" everywhere. | `ContactRecordStore.replaceContent` (:410-421, :455); `VCardMapper.kt:177-178` | Only compare memberships of user groups that can be resolved by title. Never touch groups with `SYSTEM_ID`, `AUTO_ADD`, `GROUP_IS_READ_ONLY` or `FAVORITES`. Add a regression test (S) |
| F2 | **An expiring temporary contact can delete a real contact and its call history.** After a merge (from Duplicates, multi-select merge, the picker's "add to existing", or automatic aggregation), the old lookup key resolves to the merged person. Housekeeping then deletes the whole contact across accounts, including the cloud copy, and purges its history with no undo. | `HousekeepingWorker.kt:39-48`; `ContactsRepository.join` :661-665, `resolve` :633-635 | Store the temporary contact's raw contact IDs and delete only those. If other raw contacts remain, skip the purge and ask. Clear the temporary flag on merge, or ask then (S) |
| F3 | **Read-only, SIM and messenger accounts are offered as save, import and move targets.** A "Move to…" into such an account copies the contact and then **deletes the original**. Restore can also put contacts back into non-writable accounts. | `ContactsRepository.accounts()` :308-325, :738; `ContactMover.kt:45-50`; `ContactRecordStore.availableAccounts` :518-529 | Allow only the device or local account and account types whose sync adapter `supportsUploading()`. Exclude SIM types and every messenger package. Fall back to the device account (S) |
| F4 | **"Move to private" loses data silently.** The vault copy is built from the display model, so IM, SIP, the photo, department, PO box and custom fields are dropped. There is no undo. For synced accounts, the deleted plaintext row stays readable until the next sync. | `AppViewModel.moveToVault` :388-395 | Build the vault entry from the lossless `ContactRecord`, including an encrypted photo. For local or unsynced raw contacts, purge with `CALLER_IS_SYNCADAPTER`. Say "removed from other apps after the next sync" (M) |
| F5 | **Quick saves after messaging an unsaved number go into the system contacts,** where WhatsApp and every app with READ_CONTACTS can read them, until they expire. | `MessengerLauncher.kt:63-72` | Save them as **private and temporary** by default ("Saved privately for 7 days"). Keep "Save visible to other apps" as a choice (S) |
| F6 | **A dismissed ongoing-call notification never comes back.** On Android 14+ users can swipe it away. Parley re-posts only when the content changes, so the user loses the way back to the call and the hang-up button. | `CallNotifier.kt:96-100` (signature skip), :126-137 (fallback has no exemption) | Add a delete intent that clears `lastPosted` and re-posts immediately (S) |

### Medium: wrong behaviour, privacy leaks

| # | Bug | Where | Fix |
|---|---|---|---|
| F7 | **Last-9-digit matching** mixes up different numbers in: the vault (a foreign caller shows a private name), the private-name provider (the documentation promises exact matching), repeat-caller counts and verdicts, contact-page duplicate removal, and the per-contact call history | `PhoneNumbers.matchKey` :94-97, `MIN_MATCH` :124; `VaultRepository.kt:100,117`; `BlockRepository.kt:64,147`; `Screening.kt:103`; `ContactsRepository.kt:303`; `ContactDetailScreen.kt:210` | Key on E.164, using the call's SIM country when known. The vault stores E.164 HMACs; the suffix is only a fallback when E.164 can't be derived. Migrate the stored vault HMACs (M) |
| F8 | **Per-contact metadata is keyed by the exact lookup key.** Pinned notes, the preferred messenger, keep-in-touch, call backgrounds and reminders are orphaned when the key changes (link or unlink, a Google contact's first sync, a local name change, a move) | `AppDatabase.kt:119-127,205`; `CallBackgrounds.kt:29-33`; `RemindersWorker.kt:62-67` | Resolve stored keys through `lookupContact(getLookupUri(id, key))` and re-key the rows. Carry the metadata over explicitly on join, separate and move (M) |
| F9 | **Folder sync rewrites every phone and name row on each remote change,** because it compares the canonical form on one side with the raw form on the other. Row IDs and sync state are lost, the adapters re-upload, and the operation isn't atomic | `VCardMapper.kt:97-102,174`; `ContactRecordStore.kt:419,462` | Canonicalise both sides before comparing (S) |
| F10 | **Samsung and Xiaomi phone-only accounts before Android 15** (`vnd.sec.contact.phone`, `com.android.contacts.default`) aren't recognised as local. Their contacts may count as not writable, so each save adds a linked copy | `ContactsRepository.kt:595-602`; `ContactRecordStore.kt:531-537`; `AccountDiagnostics.kt:130-136`; `ContactModels.kt:37` | Treat the OEM types as local everywhere. Verify on a Samsung device (S) |
| F11 | **The "Starred in Android" favourites group shows up as a label** that can be renamed, merged or deleted. Removing members from it also unstars them | `ContactsRepository.kt:350-355,379`; `LabelsRepository.kt:278-333` | Filter out `GROUP_IS_READ_ONLY` and `FAVORITES` groups (S) |
| F12 | **Read-only data rows (`Data.IS_READ_ONLY`) are ignored.** Edits to them silently do nothing but are logged as changes, and sync can delete and re-insert them | Editor save path; `replaceContent` | Query read-only row IDs and show those fields as locked (S) |
| F13 | **The "last messaged" record keeps up to 500 numbers in plain preferences.** It has no clear option, isn't removed along with temporary contacts, includes vault numbers, ignores retention and isn't on the privacy dashboard | `MessagingStore.kt:74-84,123`; `HousekeepingWorker.kt:43-46` | Store it encrypted with a switch, per-item delete and "clear all"; skip vault numbers; purge it with temporary contacts; show it on the dashboard (S) |
| F14 | **The lock screen reveals the vault.** The missed-call notification shows private names even in discreet mode, with no public version. The incoming notification's "Private" label may also appear on the lock screen | `MissedCallReceiver.kt:39-41`; `VaultRepository.kt:119`; `CallNotifier.kt:40,185,222` | Add public versions and respect discreet mode. Never show the "Private" label on the lock screen (S) |
| F15 | **Vault edge cases:** two private contacts with the same number resolve to an arbitrary one (`LIMIT 1` with no `ORDER BY`); expired entries still identify callers until housekeeping runs; the duplicate warning ignores the vault | `AppDatabase.kt:144,250`; `VaultRepository.lookup` :115-120; `DuplicateWarning.kt:38-47` | Prefer the newest entry; check `expiresAt` in lookup; check the vault in the duplicate warning (S) |
| F16 | **Merge fails with many linked copies.** Aggregation exceptions are sent as n² operations in one batch, so joining 33 or more raw contacts goes over the provider's limit. `addToGroup` and `merge` send 300 operations per batch with no fallback | `ContactsRepository.kt:673-684`; `ContactMover.kt:83-93` | Split into chunks of 100–250 (S) |
| F17 | **Contact-file import problems:** the duplicate index is empty on a cold start, so "skip duplicates" does nothing; files separated by semicolons or tabs, or with a single column, import 0 contacts; the vault isn't checked for duplicates | `VCardIO.kt:122,163` | Wait for contacts to load or query the provider directly; detect the separator; check the vault (S) |
| F18 | **Health check:** "Auto-delete these in 30 days" applies to a whole group with one tap and no confirmation | `HealthScreen.kt:92` | Add a confirmation that lists the contacts and their accounts (S) |

### Low

- **F19. Messaging edge cases.**
  - Parley reads the country from the default SIM rather than the SIM that took the call (dual SIM, travel), and there is no way to override it (`Phone.kt:9-14`; `MessageOnSheet.kt:112`; `NumberActionActivity.kt:141,206`).
  - A messenger row can be enabled while the tap does nothing (numbers shorter than 7 digits or longer than 15, `MessageOnSheet.kt:127`).
  - Draft text on the clipboard isn't marked sensitive (`:129`).
- **F20. Unknown-caller ringtone:** it can overlap Telecom's own ringer for a moment, and it never vibrates (`CallManager.kt:217-224`).
- **F21. Undoing a call-history delete isn't idempotent** (`CallHistory.kt:433-440`).
- **F22. Keypad tones play in silent or vibrate mode,** and the system tone setting is read only once (`KeypadTab.kt:168,181`).
- **F23. Relations open the contact whose name matches** (`ContactDetailScreen.kt:286`). Store the related contact's lookup key instead.
- **F24. Blank contacts are hidden.** A contact holding only an address, note or website is invisible (`ContactsRepository.kt:139-142`). Clearing every field leaves an empty raw contact behind.
- **F25. Address editing drops PO box and neighbourhood from the formatted address** (`ContactModels.kt:22-24`).
- **F26. Primary flags.** Merging can leave several "primary" rows; joining contacts doesn't choose a default name, so the displayed name can flip.
- **F27. Avatar initials split surrogate pairs** (`Avatar.kt:72-78`).
- **F28. A post-dial callback is never unregistered** (`CallManager.kt:104`).
- **F29. Live Recents after a late permission grant.** `ContentResolver.changes` swallows the SecurityException at registration (`ContactsRepository.kt:62-65`), so Recents won't update until reload if call-log access was granted later.
- **F30. Wording.** Since late 2025, WhatsApp shows a contact-sync prompt when opening a chat with an unsaved number (ChatLaunch #179). Newer WhatsApp may also refuse chats while its own contacts permission is revoked (WA Contact Adder #3). Soften "without saving it" and add a one-time explanation.

**Device tests to add to TESTING.md:**
- three calls with no conference (active, held and waiting), declining the waiting one;
- swapping calls across two SIMs;
- call notifications on Android 17 Pixel (Fossify #854);
- one SIM rejecting every call through an OEM screening service (Fossify #456).

---

## 3. What each project taught us

**Fossify Phone.** Parley already beats it on nearly everything. Useful ideas:
- **Keypad touch handling:** the tone starts on press; long-press is cancelled when the finger slides off the key; keys roll over; the tone lasts at least 150 ms; tones are muted in silent mode.
- **Two-stage Recents load:** the first 100 rows first, then the rest in the background.
- **The SIM number on the answer button.**
- **Location shown in Recents only when it helps.**
- **The `APPLICATION_PREFERENCES` settings gear.**
- **Clearing Telecom's missed-call count when Recents opens.**

Its most-requested features that Parley lacks: visual voicemail (#48, 30👍), missed-call notification details (#832, #839, #756) and "Block this caller?" after a call (#622). Its bugs to avoid:
- `MODE_IN_CALL` set by the app;
- `requestDismissKeyguard` on every call;
- loading every contact for each notification;
- blocked calls hidden from history;
- wildcard rules checked against raw numbers.

**Amadz.** Nothing new to adopt beyond keypad tones that follow ringer mode, contact search by postal address, and the carrier name when the SIM label is generic. Its mistakes to avoid:
- a single-call model;
- declined and blocked calls shown as "missed";
- speaker-off dropping Bluetooth;
- running its own naive ringer;
- swiping the notification declines the call;
- **a committed release keystore and password.**

**ChatLaunch.** A **Quick Settings tile** that opens a small number sheet over any app; **an inline country chip**; **a history** of chats with unsaved numbers. Its mistakes:
- the country code can only be changed through a hidden gesture;
- its link has no explicit package, so a browser can open it;
- `+1` (US and Canada) and `+7` (Russia and Kazakhstan) collide;
- `tel:` links get a `+` prepended;
- it turns off right-to-left layout for the whole app.

**WA Contact Adder.** Its whole value is **messaging a saved contact on WhatsApp while WhatsApp has no contacts permission**. Parley can't do that today (see M6).

**Alternate.**
- It proves **Google Phone looks up names from a third-party `ContactsContract.Directory`**, so an opt-in, approval-gated directory for vault names is viable.
- Its caller card shows job, location and a photo.
- It has a **"lock now"** toolbar button and an **"Add fields" sheet** in the editor.
- Its own security is weak: an unencrypted database, an app lock with a resettable lockout, a directory any app can query, and a privacy policy that claims encryption it doesn't have.

**Goodwy Contacts.** The polish Parley should match, done in Material 3:
- A **collapsing contact header**: the photo and name dock into the top bar as you scroll.
- **Inset grouped cards** with hairline dividers, and the section icon only on the first row.
- **Labelled quick-action tiles**, including **Video** when a messenger supports it.
- **Opt-in swipe actions.**
- Editor rows with a green **+** to add and a red **−** to remove.
- A **grey monogram and emoji avatars**.
- A scroll-linked top-bar tint and **adaptive fast-scroll letters**.
- **Messenger handles with profile deep links** for about 18 services.
- **vCard 4.0 relation types**, with a contact picker.

What to avoid: a Pro paywall on basic features, promotion dialogs that replace a call, and 7 sp labels.

**contacts-android.** A data-integrity reference (§2 F1–F4, F8–F12, F16, F24–F26). Features to adopt: a **"Me" card from the profile contact**, **set or clear the default number or email**, showing **other data kinds** (IM, SIP, Google "File as", user-defined fields), **broad search**, a masked raw-table dump for diagnostics, a SIM-ready check, and caller lookup through the work profile.

---

## 4. What to build

### 4.1 Calls, voicemail and notifications (V)

| # | Feature | Effort | Where |
|---|---|---|---|
| V1 | **Voicemail inbox** from `VoicemailContract`, which the default dialer can read: play, speaker or earpiece, delete, call back, mark heard, share the audio file; a "Voicemail" filter chip in Recents plus a badge. It explains honestly that Parley shows what the carrier's voicemail app or Android has already downloaded, and can't sync without internet. "Call voicemail" stays on key 1 | M | Recents chip; voicemail rows inline in Recents |
| V2 | **Richer missed-call notification:** contact photo, SIM label, time, grouped by caller with a count, "Why didn't it ring?" from the screening trace (for example "Silenced: off hours"), and the actions Call back, Message on… and Block | S | `MissedCallReceiver` |
| V3 | **Missed-call re-alert:** off by default, every N minutes until seen, respects Do Not Disturb | S | Settings › Calls |
| V4 | **Post-call card for unknown numbers:** Block (opens the rule editor), Save privately (temporary), Message on…, Report | S | The call-ended screen |
| V5 | **The SIM number on the answer control** on dual-SIM phones | S | `IncomingControls` |
| V6 | **Switch to disable the proximity sensor,** for broken sensors or listening in a pocket | S | Settings › Calls |
| V7 | **Keypad touch polish:** cancel long-press on slide-off, key roll-over, a minimum tone length, tones muted in silent or vibrate mode, and in-call DTMF held while the key is pressed (for phone menus) | S | `KeypadTab`, `CallManager.playDtmf` |
| V8 | **Pocket-dial guard:** if the proximity sensor is covered when a favourite or the widget dials, ask first | S | Call path |
| V9 | **"Why did my phone ring, or not?"** Add ring-side facts to the stored trace (Do Not Disturb state, ringer mode, which ringtone played, which device answered). Show them in number history | M | Screening trace + history |
| V10 | **Link to the system's "Power button ends call"** accessibility setting | S | Settings › Calls |
| V11 | **Clear Telecom's missed-call count when Recents opens;** two-stage Recents load | S | Recents |

### 4.2 Messaging (M)

| # | Feature | Effort | Where |
|---|---|---|---|
| M6 | **"Message on…" for saved and private contacts:** on each phone row and in the Message quick action, the vault page included. A hint when WhatsApp hasn't linked the person ("WhatsApp can't see your contacts; you can still message them") | S | Contact page, vault page |
| M7 | **Preferred messenger per contact based on links,** not just messenger data rows, so one tap opens WhatsApp for that person | S | Contact meta; the row message button |
| M8 | **"Message a number" Quick Settings tile and launcher shortcut:** opens the sheet with an empty field and a Paste chip | S | QS tile; `shortcuts.xml` |
| M9 | **Inline country chip** on the sheet ("+92 ▾"), with a picker built from libphonenumber regions; the default comes from the SIM that took the call | S–M | `MessageOnSheet`, `NumberActionActivity` |
| M10 | **"Messaged numbers" list** with per-item delete, clear all, "don't keep a record", and automatic expiry (together with F13) | S | Settings › Privacy; a Recents chip |
| M11 | **Bulk "Add several numbers…"** from pasted or shared text: review each against contacts and the vault; a naming pattern; save to a label, the vault or as temporary for N days; batched writes with a single undo | M | Contacts overflow; the number-action sheet |
| M12 | **Contact CSV with column mapping** (Google, Outlook, "Name,Phone", semicolon, tab, single column) | M | Import |
| M13 | **Telegram "open profile"** action; **"Introduce myself to a list"**, a step-by-step queue of prefilled chats where you tap Send each time | S / M | Messenger chip menu; bulk-add result |

### 4.3 Contact data (I)

| # | Feature | Effort | Where |
|---|---|---|---|
| I1 | **Messenger handles, shown and editable:** Matrix, Threema, Telegram, Signal usernames, Discord, SIP and others, with per-service input hints and deep links (`matrix.to`, `threema://compose?id=`, `tg://resolve?domain=`…), plus a test that other data rows are preserved | M | Contact page "Messengers"; an editor "Handles" block |
| I2 | **A "Me" card** from the platform profile contact (shareable vCard and QR; replaces the separate "My details" setting) | M | Top of Contacts; Settings |
| I3 | **Set or clear the default number or email** | S | Long-press on a phone or email row |
| I4 | **"Other fields"** section: read-only display of custom data kinds (Google "File as", user-defined fields…) so nothing looks lost | S | Contact page |
| I5 | **vCard 4.0 relation types** (about 60) with a contact picker that stores the related contact's lookup key (F23) | S | Editor |
| I6 | **Richer private-contact caller card:** job or company, a "who is this" context line, the pinned note and an encrypted photo (added with the system photo picker, which needs no permission) | M | In-call caller card; vault editor |
| I7 | **Opt-in, per-app-approved Directory provider for vault names,** so a dialer you approve (for example a car or work-profile dialer) can show private names. It reuses the C15 approvals, rate limit and log. Off by default | L | Privacy › Private-name access |
| I8 | **Broad contact search** (address, notes, company, website; not T9) with a "matched: address" hint | S | Contacts search |
| I9 | **Work-profile caller lookup** (`ENTERPRISE_CONTENT_FILTER_URI`) when a work profile exists and policy allows it | S | Caller info |

### 4.4 Look and feel (U)

| # | Feature | Effort | Where |
|---|---|---|---|
| U1 | **Collapsing contact header:** the avatar and name dock into a medium or large top app bar as you scroll (reusing the shared-element transition); "last talked" shows when collapsed | S–M | `ContactDetailScreen` |
| U2 | **Grouped sections everywhere:** M3 Expressive segmented surfaces, 12 dp gaps, inset hairlines, the section icon on the first row only, for the contact page, editor, Settings and the Blocking screen | M | Shared `core/ui` components |
| U3 | **Labelled quick-action tiles** (text ≥ 12 sp): Call, Message, Video (when available, with a chooser and a remembered choice per contact), Email | S | Contact page |
| U4 | **Opt-in swipe actions** on contact and Recents rows: right swipe calls, left swipe messages; Delete only with undo | M | Settings › Appearance, with a preview |
| U5 | **Editor affordances:** a tinted "−" to remove, "+ Add…" at the end of each group, "More fields" revealed inline | S | Editor |
| U6 | **Avatar styles:** a grey gradient monogram option and emoji-as-avatar; company-only contacts get a building icon; fixed surrogate-pair initials (F27) | S | Appearance |
| U7 | **Scroll-linked top-bar tint** on every list; **adaptive fast-scroll letter size** | S | Lists |
| U8 | **"Lock now"** toolbar action when the app lock is on | S | Contacts top bar |
| U9 | **System settings gear** (the `APPLICATION_PREFERENCES` intent filter) | S | Manifest |
| U10 | **Opt-in local crash capture,** offered on the next start, shareable with numbers masked; a masked raw-table dump in the diagnostics export | S | Settings › About |
| U11 | **Copy selected contacts as text** (clipboard marked sensitive) | S | Multi-select overflow |

### 4.5 Localisation (L)

- **L1.** Move every UI string to resources, starting with the telecom module (notifications, route names, in-call controls). Enable per-app language and `generateLocaleConfig`. **Effort: M.**
- **L2.** Restore the partial German, Spanish, French, Portuguese (Brazil), Hindi and Urdu translations set aside before v2.0. Add Arabic. Every string needs native review. Set up a Weblate-friendly structure. **Effort: M.**
- **L3.** RTL checks. Force left-to-right only on number fields, never on the whole app (ChatLaunch's mistake). **Effort: S.**

### 4.6 Explicitly not adopting

- Call recording.
- Visual-voicemail *syncing* (it needs internet).
- Auto-SMS on a missed call (it needs SEND_SMS).
- A caller-ID overlay (it needs the overlay permission).
- A paid tier or promotion dialogs.
- The contacts-android library as a dependency.

---

## 5. Roadmap v3.1 (proposed)

1. **Data safety first, all S unless marked:**
   - F1, F2, F3, F5, F6 (high risk);
   - F4 (M) and F7 (M; includes a vault HMAC migration with tests);
   - F8–F18;
   - then the low-risk items F19–F30.
2. **Calls:** V1 voicemail inbox, V2, V3, V4, V5, V6, V7, V8, V10, V11.
3. **Messaging:** M6, M7, M8, M9, M10, then M11–M13.
4. **Contact data:** I1, I2, I3, I4, I5, I6, I8, I9. I7 (the Directory provider) is opt-in and comes last.
5. **Polish:** U1–U11.
6. **Localisation:** L1, L3, then L2 with native reviewers.
