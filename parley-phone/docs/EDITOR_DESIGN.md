# Contact editor design (v3.4, E1)

How the leading contacts apps lay out "new/edit contact" (checked September 2026), and what Parley took from them.

## What the top apps do

| App | Notable patterns |
|---|---|
| Google Contacts (Material 3 Expressive, 2025–26) | Big round photo at the top ("Add picture"); "Save to" account chip above the form; First/Last name with a chevron that reveals prefix, middle, suffix and phonetic names; each field kind has an icon in the start margin; label dropdown under each value; rarer fields under "Add fields"/"More fields" (nickname, website, relation, notes, labels, custom fields); ✕ and a filled "Save" in the top bar; "Discard changes?" on leaving. The Expressive update wrapped the list and page in rounded containers and pill buttons; the create page itself changed little. |
| Samsung One UI 7/8 Contacts | Storage location ("Save to Phone / Samsung account / Google") as a pill at the top; photo circle with a camera badge; name as one field with an expand arrow for name parts; type as a small dropdown next to each number; grouped rounded cards; "View more" reveals remaining fields in one go; Cancel/Save at the top. |
| iOS 18/26 Contacts | Large photo/poster with "Add Photo"/"Edit"; First, Last, Company stacked; each group as an inset rounded list; green "+ add phone" rows and red "−" to delete a row; tapping the label ("mobile ›") opens a label picker with custom labels; "add field" at the bottom for pronouns, phonetic names, nickname, job title…; Cancel and Done in the bar, Done disabled until something changes; birthday with optional year. |
| Fossify / Goodwy | Material cards; type spinner left of each value; +/− icons; photo with change/remove menu; all fields visible, which gets long. |
| Microsoft Outlook (Android) | Sectioned form, "Add phone/email" rows, save to account chooser, minimal photo handling. |

Common ground worth copying:
1. **Photo first**, big and round, with an obvious edit badge; removing is a separate, clearly red action.
2. **Account choice visible up front** for new contacts, read-only for existing ones.
3. **Name collapsed to the common case** (first/last) with an expander; keep the expander open when details exist so nothing looks lost.
4. **One card per kind of field**, the type as a small inline control (chip/dropdown) next to or under the value, **"+ Add …" rows** at the end and **red "−"** per row.
5. **Progressive disclosure**: rarer kinds only through "Add fields"/"More fields"; offer only kinds not already on screen.
6. **Save in the top bar, disabled until there's something to save**, and a discard confirmation on Back/✕.
7. Right keyboards (phone, email, URL), IME "Next", new rows focused on add, inline (non-blocking) validation, duplicate warning while typing, date picker with optional year.

## What Parley does

- Top bar: ✕ (Cancel) and a filled Save, disabled until a new contact holds something or an existing one really changed (`EditorForm.canSave`; blank rows that were never saved don't count, `EditorForm.meaningful`). A spinner replaces "Save" while saving.
- Header: 128dp photo with a camera/pencil badge, "Add photo"/"Change photo" and a red "Remove photo"; the name typed so far under it; the "Save to" chip (Private or an account, with counts) or "Saved in …"; the duplicate card; the Name card (chevron for details) and the Work card.
- Groups: Phone, Email, then (when present) Dates, Address, Handles, Website, Relations, Labels, Notes, "When they call" (private), Call-screen background. Each is one rounded card split into lazy items (head, one per row, "+ Add") so rows animate in and out with `animateItem` while reading as one card. Rows keep stable keys (`RowKeys`), so focus and animations follow the right row.
- Type chips under each value (menu with the current type ticked and "Custom…"); relations open the searchable relation list; handles pick the service.
- "Add more info": a bottom sheet with only the kinds not shown yet (`EditorForm.addable`); picking one adds a row, scrolls to it and focuses it (dates open the picker).
- Gentle hints (not errors) for an email or phone that doesn't look right, shown once you leave the field.
- Unsaved-changes guard with predictive Back: the editor scales down with the gesture, then asks.
- Two columns at ≥ 720dp (landscape, tablets, foldables); otherwise one column capped at 640dp.
- Unchanged underneath: lossless saving (read-only rows locked, unknown fields kept, raw-contact editing, PO box/neighbourhood), private/vault contacts with encrypted photos (`ContactPhotoProcessor`), temporary-contact keep prompt, QR handshake `&hs=`, journal/undo.
