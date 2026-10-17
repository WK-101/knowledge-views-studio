# Export richness audit (7.3)

Parley now holds rich data: contacts with structured and native-language names, labels, relations,
dates/occasions, languages and citizenship; case files with sealed reference numbers and hold-time facts; per-call
facts; Circle rhythms and promises; archived contacts and numbers. This audit compares what each export/share feature
carries against what the app holds, and records what was enriched this release and what is left as an owner decision.

Legend for recommendations: **include** (clearly valuable and safe — shipped or already present), **omit by design**
(left out on purpose, usually for privacy or format cleanliness), **owner decision** (debatable; not shipped).

Privacy invariants that bound every recommendation (all verified in code):

- Private contacts leave the phone **only** through the deliberate "Include private contacts" choice on the contact
  export screen, and **only** after the vault unlock (`AppLock.authenticateForVault`). After a duress unlock they do
  not exist for the exporter (`ContactExport.privatesFor` returns empty when `Privacy.duressOnly().hiding`), and the
  switch itself is hidden while `vm.privacy…hiding`.
- A sealed case reference number is opened for the report **only** when the person turns on "Include reference
  numbers" (which asks first); otherwise only the count of what was left out is written.
- A normal (plain) export never carries sealed data. The **encrypted backup is the only artefact that carries sealed
  data as sealed**; nothing here changes that.
- Case files are hidden under a duress unlock (the screen will not open), so no case PDF can be built then.

---

## 1. Contact export — vCard 4.0 (`VCardMapper` + `CardNotes`)

The vCard path is **lossless**: `fromVCard(toVCard(r)) == canonical(r)`. Standard properties are used where one exists;
anything without a standard home rides along as an `X-PARLEY-*` parameter so nothing is dropped.

| Field the app holds | In the vCard? | Property |
| --- | --- | --- |
| Structured name, prefix/suffix/middle, phonetic parts, SORT-AS | yes | `N`, `X-PHONETIC-*`, `SORT-AS` |
| Name in their own language / script | yes | `FN`/`N` `ALTID`+`LANGUAGE` and a labelled `NICKNAME` |
| Nickname, pronouns, spoken languages | yes | `NICKNAME`, `PRONOUNS`, `LANG`/`LANGUAGE` |
| Citizenship | yes | `X-PARLEY-CITIZENSHIP` (ISO code) |
| Phones, emails, postal (+ RFC 9554 parts) | yes | `TEL`, `EMAIL`, `ADR` |
| Organisation, title, role | yes | `ORG`, `TITLE`, `ROLE` |
| Websites and social-profile links | yes | `URL`, `SOCIALPROFILE` (`SERVICE-TYPE`/`USERNAME`) |
| Birthday, anniversary, other dates/occasions (+ alt-calendar) | yes | `BDAY`, `ANNIVERSARY`, `X-ABDATE` (+ `X-PARLEY-CALENDAR`) |
| IM, SIP | yes | `IMPP` |
| **Relations** | yes | `RELATED` |
| Notes, custom fields | yes | `NOTE`, `itemN.X-PARLEY-CUSTOM` |
| **Labels / groups**, starred | yes | `CATEGORIES` + `X-ABLabel`, `X-PARLEY-STARRED` |
| Photo, custom ringtone, send-to-voicemail | yes | `PHOTO`, `X-PARLEY-RINGTONE`, `X-PARLEY-SEND-TO-VOICEMAIL` |
| Parley: note-for-calls, private "who is this", keep-in-touch rhythm, call notes, logged moments, open promises, private/archived flags | yes | `X-PARLEY-*` + one readable `NOTE` summary (`CardNotes`) |

Omitted: nothing material. **Recommendation: include — already complete.** Every field the brief named (relations,
labels, dates, native-language/script names, citizenship, profile links) is already carried, with round-trip tests in
`VCardMapper`/`ContactExport` tests.

---

## 2. Contact export — CSV (`CsvExports`, `ContactCsv`)

Three layouts: **Parley**, **Google** and **Outlook**. Google and Outlook use those products' exact column sets so a
file imports there without mapping; they are fixed external schemas and must not grow Parley-specific columns, or the
file stops importing cleanly. Both already include Relation, Website, Birthday and Labels columns of their own.

Parley CSV (`ContactCsv`) columns: name parts, Nickname, Organization/Title/Department, phones, emails, addresses
(with parts), **Birthday, Notes, Groups (labels), Native name, Native language, Languages, Citizenship**.

| App data omitted from the Parley CSV | Recommendation |
| --- | --- |
| Relations; non-birthday dates/occasions; websites/social profiles; pronouns; IM/SIP | **omit by design** — CSV is a flat interchange; the vCard carries all of these losslessly. |
| Parley notes, call notes, moments, rhythm, promises | **omit by design** — CSV has no place for them (as documented in `ContactExport`); the vCard and the Notes text export carry them. |

Adding relation/website/occasion columns to the Parley CSV is possible but low value (the vCard is the rich format).
**Owner decision** if a single-file, spreadsheet-friendly "everything" CSV is wanted.

---

## 3. Call-history export (`CallExport` → CSV/JSON/ICS, `ExportFiles` → PDF, print)

Per call, `ExportRow` carries: date, talk duration (always the real, un-rounded seconds), type, number (blank for a
withheld number), contact/cached name, SIM label, and the call notes attached to that call.

| App data about calls | In the export before 7.3? | Recommendation / action |
| --- | --- | --- |
| Count, total talk time, missed count | no | **include — shipped (PDF).** `HistoryText.totals` on the PDF header. |
| The applied filter (types · SIM · period/range · duration) | no | **include — shipped (PDF).** `HistoryText.describe` on the PDF header ("Covers: …"), for Recents exports. |
| Per-call hold time and IVR menu keys (`call_facts`) | no | **owner decision** — valuable for a dispute record, but needs a `call_facts` join and a new CSV column that could change how the importer detects a "Parley" file; not shipped. The case-file report already surfaces hold time and menu keys for a case. |
| Totals / filter in CSV, JSON, ICS | no | **omit by design** — those are machine-readable/standard (RFC 4180 CSV, a bare JSON array, RFC 5545 ICS); a summary would pollute the data. The PDF is the human record, so it carries them. |

Withheld numbers are already blanked, and durations are the stored talk time, never billing-rounded — unchanged.

---

## 4. Case-file report (`CaseReport` → `CasePdf`)

The complaint PDF carries: title, made-on date, the organisation's numbers, **where the case stands** (status + since),
the summary (**calls span, hold-time summary, IVR menu keys**), **reference numbers (opt-in; opened only when chosen,
otherwise counted)**, open promises, and the full timeline (every call, note and reference-added entry, oldest first as
a complaint reads).

| App data | In the report? | Recommendation |
| --- | --- | --- |
| Reference numbers | yes, opt-in | **include — already present** (and sealed until chosen). |
| Hold-time summary (total/avg/longest) and per-call hold | yes | **include — already present.** |
| "Follow up by" date | no | **omit by design** — it is a private reminder for the owner, not something to hand the organisation in a complaint. |

**Recommendation: include — already complete.** The 7.3 work on this file is the single-tap build fix (below), not
new fields.

---

## 5. Share-as-text / Notes text export (`NOTES_TEXT`, `CardNotes.summary`)

Carries, per person: note-for-calls, the private "who is this" line, the keep-in-touch rhythm, open promises, and dated
call notes and logged moments — newest first. **Recommendation: include — already complete.** A private contact's lines
are only ever written when that contact was deliberately included and the vault is unlocked (§ privacy invariants).

---

## 6. Double-click PDF export bug (fixed this release)

Not a richness item but found in the same area. The case-file "Export as PDF" action was gated on the screen's
reactive timeline (`rememberCaseTimeline` → `produceState`, which starts `null`): the top-bar icon was `enabled =
timeline != null` and the sheet only opened `if (timeline != null …)`. Before the timeline finished loading off the
main thread, the first tap landed on a disabled control and did nothing; only the second tap (after the load) worked.

Fix: the icon is enabled whenever there is a case and the sheet opens on the first tap; the export job reads the
timeline itself when the screen's is not ready yet (`CaseExport.lines`). The sheet now stays open showing progress and
disables its rows while building, so one tap builds and the result is offered, and a second tap can't start another
build. The history export sheet got the same progress/disable guard.

---

## Shipped vs deferred

Shipped this release: call-history **PDF totals + applied-filter** line; the single-tap **PDF export** fix with a
progress/anti-double-fire guard on both export sheets; **export-notification consistency** (app accent colour via
`setColor` on the shared `PrivateNotice`, a fitting export small-icon for the file-ready and folder-export notices).

Already complete (no change needed): the rich vCard, the case report's references + hold-time summary, the notes text
export, and the native-name/languages/citizenship/groups columns in the Parley CSV.

Owner decisions (not shipped): per-call hold time and IVR menu keys in the general call-history export; a fuller
"everything" Parley CSV with relation/website/occasion columns.
