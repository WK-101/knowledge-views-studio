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

## 4.2 redesign: calmer, list-style fields

Feedback on 4.1: the editor "looks old and boxy". Every group was a grey card holding outlined text fields, a title row with an icon in a tinted circle, and a type chip on its own line under every value, so a phone number took three stacked boxes.

### What the current apps do (checked September 2026)

| App | Field style | Grouping | Type / label | Add & remove |
|---|---|---|---|---|
| Google Contacts (M3 Expressive, 2025–26) | Outlined fields; the Expressive update restyled lists and the contact page (cards, pill buttons) but left "Create contact" as it was | No cards; **one icon per kind in a 56–72 dp start gutter**, fields aligned after it | Label dropdown next to/under the value | "Add phone" text rows; grey ✕ at the end |
| iOS 26 Contacts | **Borderless** text in inset grouped rows, hairline separators | Inset grouped (rounded) sections on a grey page; poster/photo on top | The label ("mobile ›") sits beside the value | Green ⊕ "add phone" rows, red ⊖ to delete |
| Samsung One UI 8 | Underlined/borderless fields inside rounded cards | Rounded groups, "View more" reveals the rest | Small type dropdown at the value's end | ⊕ / ⊖ circles |
| Fossify Contacts | Material fields, all visible | Flat list | Type spinner beside the value | + / − icons |

Guidance used: Material 3 says outlined fields have less emphasis and suit long forms, filled fields suit short forms and dialogs, and both variants shouldn't be mixed in one region; Compose's docs make the filled `TextField` the default and recommend formatting phone numbers for display only (an output/visual transformation) rather than rewriting the text. M3 Expressive's segmented lists (large outer corners, small inner ones, 2 dp gaps) are already how Parley's Settings look.

What makes an editor feel modern rather than boxy, across all of them: **no box inside a box**, one icon per group rather than per field, the type beside the value, quiet add/remove controls, and the page background showing between groups.

### Parley's 4.2 editor

- **Fields**: `ParleyFormField` (core/ui) — filled tonal, no underline or outline at rest (`surfaceContainer`), 56 dp high, label inside. Focused: container one step up (`surfaceContainerHigh`) and a 2 dp primary ring, animated with `ParleyMotion.fastSpatial`; errors get an error-coloured ring. Supporting text sits under the field, not inside a card.
- **Groups as segmented blocks**: a group's fields stack with 2 dp gaps and `formFieldShape(index, count)` corners — 16 dp outside, 4 dp at the joins (two fields sharing a line, like postcode + city, use `FieldSide.Start/End`). Groups are 16 dp apart. No card, no group title row.
- **Gutter and end column** (`FormRow`): 40 dp start gutter holding the group's 24 dp icon on its first line only (TalkBack reads it as the group's heading), then the fields, then a 48 dp end column for ⊖ (remove, `RemoveCircleOutline` in `onSurfaceVariant`) or the name chevron. The end column is kept even when empty so every field's end edge lines up.
- **Type pill**: "Mobile ▾" as a small `surfaceContainerHighest` pill at the field's end (menu with the current type ticked and "Custom…"). `TypedLine` moves it under the field when the field would be narrower than 232 dp or the font scale is ≥ 1.5.
- **Header**: 120 dp photo with a badge, "Add/Change photo" and red "Remove photo"; the "Save to" pill; the duplicate warning; then the name and work blocks directly on the page. The name block grows prefix / middle / suffix / phonetic / nickname around first and last name.
- **Add rows**: `FormAddRow` — "+ Add phone" in the primary colour, 48 dp, lined up with the fields; "Add more info" as a tonal button lined up the same way.
- **Typing**: a new contact focuses First name once; IME Next through the fields; phone numbers formatted for the SIM's country while typing (`PhoneTyping`, display only: what's saved is what was typed, and numbers typed with their own spaces, dashes or pauses are shown as typed); phone, email and web fields read left to right in RTL languages and skip autocorrect; the list pads for the keyboard and focused fields scroll into view.
- **Save** stays in the pinned top bar, greyed until there is something to save.
- Unchanged: two columns ≥ 720 dp, discard guard with predictive back, draft restore and changed-elsewhere merge, lossless saving, locked rows (lock icon, type shown under the value), relations with the contact picker, year-optional dates, address map links, call-screen picture, labels, private contacts' "When they call", custom labels and services.

Tokens: `FormTokens.gutter` 40 dp, `endColumn` 48 dp, `fieldHeight` 56 dp, `segmentGap` 2 dp, `groupGap` 16 dp, `outerCorner` 16 dp, `innerCorner` 4 dp.
