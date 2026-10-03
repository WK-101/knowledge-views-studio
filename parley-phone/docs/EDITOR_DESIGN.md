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

## 4.3 compact editor

Feedback on 4.2: the editor is calmer, but "not properly optimized for efficient space usage and wastes a lot of space leading to a longer page", and "Save to" offers only the device account(s) and Private, not a temporary contact.

### Where 4.2 spent the height (measured from the code, 360 × 800 dp phone)

| Part (new contact) | 4.2 | Why it was waste |
|---|---|---|
| Photo header: 8 dp + 120 dp photo + "Add photo" text button row (48 dp) | 176 dp | A hero band for a picture most new contacts never get; the text button repeats what the badge says |
| "Save to" chip, centred, with 8 dp above | 56 dp | Fine, but on its own line under the photo |
| Gap before the name (`groupGap` + 8) | 24 dp | |
| Name: 2 × 56 dp + 2 dp | 114 dp | 56 dp fields where 48 dp dense fields read just as well |
| Work (company + title), always shown | 130 dp | An empty group on every new contact |
| Phone: 56 + 2 dp, then "+ Add phone" row 48 dp + 12 dp | 118 dp | One tall add row per kind, even while the only row is still empty |
| Email: the same | 118 dp | |
| "Add more info" button, then the sheet | 48 dp | A second add control, and a sheet round trip for the commonest kinds |
| End spacer | 48 dp | |
| Type pill wrapping under the field (≥ 1.5 font or < 232 dp) | +40 dp per row when it wraps | |
| Account labels row (FlowRow, min 56 dp), always shown when the account has labels | 76 dp | Empty for most new contacts |
| Notes (3 lines), always shown on existing contacts; call-screen picture editor, always | ≈ 120 + 140 dp | Empty for most contacts |

**Typical new contact (name + 1 phone + 1 email): ≈ 848 dp** (924 dp with a Google account's labels): more than one screen, so Save-and-check means scrolling. **A full contact** (name, company, 2 phones, 2 emails, a birthday, an address with its map link, a website, labels, a note, the call-screen picture): **≈ 1780 dp**.

### What the current apps do (checked September 2026)

- **iOS 26 Contacts**: only the fields a contact holds are shown, plus the essentials (name, company, "add phone", "add email"); every other kind is behind one "add field" list at the bottom. Rows are compact (44 pt), the label sits beside the value.
- **Google Contacts** (M3 Expressive rollout, Aug 2025, version 4.61): the list and the contact page got rounded containers and pill buttons; "Create contact" kept its layout (photo, name, phone, email, "Add fields"). The rarer kinds are already behind one control.
- **Samsung One UI 8**: storage ("Save to Phone / Samsung account / Google") at the very top as a small control; the rest behind "View more".
- **Material 3**: dense text fields are 48 dp (4 dp above the label, 4 dp under the value: Compose's `DenseTextFieldContentPadding` sample uses `heightIn(min = 48.dp)` with `contentPaddingWithLabel`); density suits long forms and data-rich screens, as long as targets stay 48 dp. Filled fields and segmented lists (large outer, small inner corners, 2 dp gaps) are what Parley already uses.
- **Linear / Notion**: properties are one compact line each; empty properties aren't shown until you add them from one "Add property" control.

Common ground: show what is there, keep one add control, keep the type next to the value, keep the save location small and at the top.

### Parley's 4.3 editor

- **Save to line** at the top, start-aligned: a quiet chip "Save to: Google (132) ▾" whose menu lists the accounts, **Private** and **Temporary** ("Deletes itself after a time you choose"). With Temporary a second chip shows the time inline, "After 7 days ▾": 1 day, 7 days, 30 days or Custom… (1–3650 days), plus "Save visible to other apps" (off: private in Parley, like the keypad's "Save temporary contact") and "Also delete its call history" (on). One line of small print says where it goes. Saving goes through `TemporaryContacts.saveDetails`, the same entry point as the keypad (vault entry with an expiry, or a phone-only contact recorded by `TemporaryContactStore`), with the photo and every other field.
- **Existing contacts**: "Saved in …" plus one chip for its expiry: "Make temporary", or its time left ("3 days left"). The menu offers the same times and "Keep permanently"; the choice is saved with the edit, through the same store the contact page's "Delete automatically" uses (`TemporaryContactStore.mark/clear`, `VaultRepository` expiry for private contacts). Choosing a time here answers "Keep this contact?", so that prompt isn't asked as well. Not offered when editing one copy of a linked contact (the expiry belongs to the whole contact). **"Make private" is not offered in the editor**: moving a contact into the vault deletes the system contact and re-creates it encrypted (with its record, metadata and interactions); that stays on the contact page's "Move to private", with its own confirmation, rather than riding along with a Save.
- **Photo beside the name**: an 80 dp photo (with a small edit badge) centred on the First/Last name block; tapping it picks a photo, or with one opens "Change photo" / "Remove photo". Above the name (72 dp) on screens narrower than 300 dp or at font scale ≥ 1.3. The chevron at the block's end opens prefix, middle, suffix, phonetic names and nickname.
- **Dense fields**: `ParleyFormField` is a `BasicTextField` with the filled decoration and Material's dense padding: **48 dp**, label inside. Gutter 36 dp (24 dp icon + 12 dp), end column 48 dp, 2 dp joins, **8 dp between groups**.
- **Only what the contact holds**: name and phone always; email, work, dates, address, handles, website, relations, labels, notes, "When they call" (private) and the call-screen picture only when they hold something or were just added.
- **One add control**: a line of small chips at the end, commonest first: Phone, Email, Work, Date, Address, Notes, Website, Relation, Messenger handles, When they call, Labels, Call screen. A repeatable kind stays offered (it adds another row) unless its group still has an empty row. Picking a chip adds the row, scrolls to it and focuses it (dates open the picker). The line scrolls sideways; at font scale ≥ 1.3 it wraps. The per-group "+ Add phone" rows and the "Add more info" sheet are gone.
- **Type selector inside the field**: "Mobile ▾" as quiet text at the field's end (48 dp target, 96 dp max before it ellipsises). It moves under the field only when the field is narrower than 232 dp or the font scale is ≥ 1.3 (`EditorForm.typeBelow`).
- Everything else is as before: two columns ≥ 720 dp, discard guard with predictive back, draft restore (the Save-to and expiry choices are in saved state too) and changed-elsewhere merge, duplicate warning, locked rows, map links, relations picker, year-optional dates, call-screen picture, custom labels and services.

Tokens: `FormTokens.gutter` 36 dp, `endColumn` 48 dp, `fieldHeight` 48 dp, `segmentGap` 2 dp, `groupGap` 8 dp, `outerCorner` 16 dp, `innerCorner` 4 dp, `headerPhoto` 80 dp, `typeMaxWidth` 96 dp.

### Height, before and after (360 × 800 dp phone, font scale 1.0)

| | 4.2 | 4.3 | Change |
|---|---|---|---|
| New contact, name + 1 phone + 1 email | ≈ 848 dp | ≈ 350 dp (Save to 52, photo + name 98 + 8, phone 56, email 56, add chips 48, end 24; + 12 while the phone is empty) | **−59 %**, fits on one screen with the keyboard closed |
| Same, Google account with labels | ≈ 924 dp | ≈ 350 dp (labels are a chip until used) | −62 % |
| Full contact (see above) | ≈ 1780 dp | ≈ 1080 dp (work 106, 2 phones 106, 2 emails 106, date 56, address with map link 204, website 56, labels 56, notes 80, picture 150, add chips 48) | **−39 %** |

At font scale 1.3 fields grow with the text, the type selector moves under the value and the add chips wrap, so the page is longer there by design; nothing is cut off.

## 4.4 corrections

Feedback on 4.3 (screenshot of a new contact): a big magenta avatar with a tiny camera badge beside the two name
fields; the name chevron floating outside the fields; the name fields starting further left than the phone field (the
phone had an icon gutter, the names didn't); "Save to: Device (64)"; the Add chips cut off at the screen's edge.

- **One left edge.** Every line keeps the icon gutter (`FormTokens.gutter` 40 dp: 24 dp icon + 16 dp, as on the
  contact page): the name block has the person icon, phones the phone icon, and so on. The name block is a form row like
  every group, with its chevron in the end column (where the others have ⊖), centred on the block.
- **Photo on top, compact and neutral.** A 64 dp tonal circle with a camera (`FormTokens.headerPhoto`), centred above the
  form like the contact page's header, or the photo with a small edit badge. Beside the name it pushed the name fields
  off the common edge and dominated the first screen; on top it costs 76 dp and leaves every field aligned.
- **Save to without counts.** "Save to: Device", "Google · you@example.com", "Private", or "Temporary" with its time chip
  ("7 days ▾"); one line, ellipsised. The line starts on the fields' edge.
- **Fields match the contact page.** 56 dp (`fieldHeight`; 8 dp above the label and under the value), 20 dp outer
  corners like the page's groups, 2 dp joins, 8 dp between groups.
- **Add chips wrap** (FlowRow) at every font size, so no kind is hidden past the edge.
- **My card** uses this editor (`EditorArgs.meCard`, route `PeopleRoutes.MeEdit`): the same name, phone, email, work,
  website, address and note rows (no photo, no type selectors, one address line, no name details), and in place of
  Save to, chips for what the QR code and shared vCard include (`MeCardStore.shareParts`). The My card page is laid
  out like a contact's page (compact header with QR code / Share / Edit tiles, then Contact info).

Tokens now: `gutter` 40 dp, `endColumn` 48 dp, `fieldHeight` 56 dp, `segmentGap` 2 dp, `groupGap` 8 dp,
`outerCorner` 20 dp, `innerCorner` 4 dp, `headerPhoto` 64 dp, `typeMaxWidth` 96 dp.

## 4.5 profiles

Feedback: "Give an option to add more details about a contact such as Instagram id or LinkedIn or other popular
important services (also in My card)."

- **Kept as websites.** A profile is the contact's website row with the profile's https address and the service's name
  as its custom label ("Instagram"), `SocialProfiles` in core/common. Google Contacts, Samsung and iOS show a labelled
  website and open it, Google and CardDAV sync it, and the vCard engine round-trips it (`itemN.URL` + `X-ABLabel`). A
  data row of Parley's own would be dropped by sync adapters and shown by no other app; an Im row is a chat handle,
  which most apps show as such and Google Contacts doesn't sync for custom services. Rows other apps wrote are
  recognised by their address (github.com/ana with any label is a GitHub profile).
- **Services**: Instagram, LinkedIn, X (Twitter), Facebook, TikTok, YouTube, Snapchat, Threads, Bluesky, Mastodon
  (user@server), GitHub, Reddit, Pinterest, Twitch, Behance, Dribbble, plus "Other link" (an ordinary website with
  its own label). Messenger handles (Matrix, Threema, Signal…) stay Im/SIP rows as before.
- **Editor**: a "Profile" chip opens "Add a profile" (the services with their badges, most used first, then Other
  link). A profile row is the handle field with the service as its selector; a pasted profile link gives its handle
  (and a link of another service moves the row to that service); a gentle hint says when the handle doesn't look like
  the service's ("Add the server too" for Mastodon). Which rows are profiles is decided once per row, so a website
  being typed never jumps between groups.
- **vCard**: import reads iOS `X-SOCIALPROFILE` and RFC 9554 `SOCIALPROFILE` into labelled website rows (once, when
  the card also has the URL). Since 5.3 export writes RFC 9554's `SOCIALPROFILE;SERVICE-TYPE=<label>;USERNAME=<handle>`
  with the address as the value (see "More fields"); 4.5–5.2 wrote the labelled URL, which is still read.
- **My card**: the same rows; the QR code and shared card include them when "Profiles" is chosen among the shared
  parts. **Private contacts**: the same rows, sealed with the details. **Search**: Contacts search finds a profile by
  its handle, with or without "@" ("Matched: profile").

## 4.6 Paste details

Cardhop's best trick, offline: a **Paste details** chip at the top of a new contact (and "Make a contact from this
text" on Parley's share sheet) reads a pasted email signature, business profile, "Contact us" block or badge into the
form. The clipboard is read only when the chip is tapped.

- **Reading**: `PasteParser` (core/common, pure Kotlin, deterministic) splits lines at separators, takes labelled
  fields ("Tel.:", "Mobil", "Company:"), emails, links (map links through `MapLinks`, profiles through
  `SocialProfiles`), phone numbers through libphonenumber in the SIM's country (labels before or after the number,
  extensions kept after a pause), postal addresses (street, postcode and country lines, the city between them), a
  birthday only after its word, then decides which line is the name (the one the email address names, the first full
  name), the organisation (legal forms, the email's domain) and the title. Sign-offs, "Sent from…" lines and
  disclaimers are dropped; anything else is the note. Several people in one text are offered one at a time.
- **System classifier**: the phone's on-device `TextClassifier` (`generateLinks`, no network) adds where it saw
  addresses and numbers; the rules use it only where theirs found nothing.
- **Preview**: a sheet lists every value with its detected type and a checkbox; **Fill in** fills empty fields and
  empty rows (never replaces what was typed, never adds a value twice). When a ticked number or email already
  belongs to a contact (or a private contact, unless they are hidden), **Add to <name>** continues in that contact's
  editor instead. Nothing is saved until Save, so Private and Temporary work as for any new contact.

## Work: department, office and job description

- The Work group holds **Company, Title and Department** as one three-part card (Google's 2024 editor offers
  department under "Add fields"; Parley keeps the three together because they are one Android row). The group shows
  when any of the three has a value, or when Work is added.
- **Office location and job description** come from Outlook, Exchange and vCard (`ROLE`) imports. The editor doesn't
  change them; the contact page lists them under Other fields ("Office", "Job description").
- Saving writes only company, title and department (`WorkRow.EDITED`). A work row that still holds anything else (a
  label, job description, ticker symbol, phonetic company name or office, `WorkRow.KEPT`) is kept with those three
  cleared instead of deleted, and an unchanged row is never touched. Earlier versions deleted a row with a department
  but no company or title on any save.

## Save to on Android 16

Android 16 lets the user choose a default account for new contacts. While that default is a cloud account, Android
refuses new contacts on the phone only, so the Save-to menu leaves out **Device**, lists the default account first and
says why under the accounts. Settings › Contacts › "Save new contacts to" says the same. Every insert path (this
editor, imports, restores, Make visible, temporary contacts, Add several numbers, shared labels) goes through
`DeviceAccounts.newContacts` and `NewContactAccount`, so none of them fails there.


## Photo: camera and framing

4.3 kept every photo whole and at full quality, which is still the rule for the picture itself. But Android, other
apps and Parley's own lists show a contact photo in a circle or square cut from the middle, so an off-centre face lost
its top or its side. Google Contacts, Samsung and iOS all let you crop and reposition after picking; none of them keeps
the whole picture as well.

- **Photo menu.** Tapping the header photo opens one menu: **Choose photo** (the system photo picker), **Take photo**
  and, with a photo, **Adjust framing** and a red **Remove photo**, then the line saying what Parley keeps.
- **Take photo** asks the phone's camera app for one picture (`ActivityResultContracts.TakePicture` into a file of
  Parley's cache shared through its FileProvider, `ContactCamera`), as Scan QR does: no camera permission. The cache
  isn't sealed, so the file is kept only while the editor needs it: it is deleted once the contact is saved, when its
  framing is cancelled, when another photo replaces it or it is removed, and when the editor is left without saving.
  A shot that a stopped process left behind is cleared at the next start or by the daily upkeep once it is a day old
  (an editor restored after the process was stopped still finds its photo until then); the framed avatar written for
  Android during a save is deleted once written, and any left by an unfinished save after ten minutes.
- **Frame photo** (`PhotoFramer`) follows every new picture: the whole picture behind a dimmed cover with a circle cut
  out (the square around it, faintly, is Android's thumbnail). Drag to move, pinch to zoom (up to 8×, never smaller
  than 48 px of picture); the point under the fingers stays under them. Every gesture has a 48 dp button (zoom out, zoom
  in, move left/up/down/right, Centre on face, Reset) for TalkBack and switch access; the area reads its zoom. The
  picture area and the move buttons are laid out left to right in every language, so "left" is the screen's left. A
  face found by Android's own on-device detector (`android.media.FaceDetector`, no library, no network) places the
  first circle and enables Centre on face. **Use whole photo** keeps the 4.3 behaviour; ✕ drops the new picture.
- **Saving.** The framed square (`PhotoFrame`, fractions of the upright picture; math in `FrameMath`, core/common) is
  cut from the picture decoded at just the size it needs (`ContactPhotoProcessor.processFramed`) and written as
  Android's photo, or as a private contact's sealed caller photo. The picture as picked is kept whole beside it
  (`OriginalPhotos`) with its frame, so the contact page, the photo viewer and **Adjust framing** start from the whole
  picture; the frame travels with Make private / Make visible. Adjusting only the framing writes a new square from the
  kept original and keeps the original matched to it. A photo another app set (no original) is framed from Android's
  copy. The editor's header shows the framed circle as the lists will.
- **Everywhere the editor is**: device, private and temporary contacts. My card has no photo (it isn't shared), so it
  has no photo menu.
- **Save and share**: the photo viewer saves or shares the original as kept (see "Save and share (5.3.1)").

### Save and share (5.3.1)

Feedback: "When I open a saved contact and open its image then give option to save/share that image. In full original
format. Not only in contact but every image place."

- **Where.** Every place a picture opens or Parley shows one it made: the contact page's photo viewer (kept original or
  Android's photo; device, private and temporary contacts alike), the call-screen picture (tap its thumbnail in the
  contact page's settings; the Poster background is the same picture), and the QR codes: a contact's plain and secure
  QR, My card's (also when swapping cards), a shared label's invitation, a rule template's and simple mode's setup.
  The in-call screen opens no picture (tapping the photo opens the contact), so a masked lock-screen call offers
  nothing. One helper serves them all: `ImageExport` and `rememberImageActions` (`app/.../ui/common/`), with
  `ImageFiles` (core/common) reading the format from the bytes.
- **What is handed out.** A kept original exactly as stored (`OriginalPhotos.exportBytes`): no decoding, no
  re-encoding, the same format, MIME type and EXIF. Since 4.3 an original is kept byte for byte (JPEG, PNG, WebP) with
  only its location tags removed, or once as a high-quality JPEG (HEIC, over 20 MB), so the file carries the camera and
  date but no location; the viewer says so. Without an original (a photo another app set, or from before 4.3): the
  largest copy Android has (the display photo, `PHOTO_FILE_ID`, through `openContactPhotoInputStream`), and for a
  private contact the copy sealed for caller ID; the viewer says which. The call-screen picture is the JPEG Parley keeps
  for the call screen; QR codes are lossless PNGs. The type and the extension come from the bytes (JPEG, PNG, WebP,
  GIF, HEIC, HEIF, AVIF magic numbers), the name from the person ("Ana Lima.jpg", "Ana Lima call screen.jpg").
- **Save** opens Android's "Save to" screen (`ACTION_CREATE_DOCUMENT` with the picture's own type and name): no storage
  permission, the permission guard is unchanged. **Share** writes the picture into its own folder of Parley's cache and
  hands a FileProvider `content://` URI with a read grant to the app chosen. Parley's cache isn't sealed, so a private
  contact's copy is deleted when the share screen returns and whenever Parley locks, and any copy left goes before the next share, at the next
  start (all) and in the daily upkeep (a private contact's after 10 minutes, others after an hour).
- **Private contacts** ask for the private contacts' unlock before every save or share, and offer neither while
  discreet mode hides private contacts (or after a duress unlock). A plain or secure QR of a private contact follows
  the same rule. Codes opened with a passcode (secure QR, invitations, simple mode) say under the buttons that the
  picture holds the code alone, so the passcode goes another way.

## More fields (5.3)

Google's 2024 "Create contact" put department, phonetic middle name and custom fields under **Add fields**; RFC 9554
(2024) added a language, a second surname, a generation and eleven address parts to vCard. Parley 5.2 kept most of
them when they arrived but couldn't show or edit them. The rule stays the editor's: show what the contact holds, add
the rest from the one line of chips.

- **Custom fields** (chip "Custom field", repeatable): each is a two-line block, Label ("Shoe size") then Value, with ⊖.
  In a Google account they are Google's user-defined field (`contact_user_defined_field`, DATA1 label, DATA2 value), so
  they sync and Google Contacts edits them; anywhere else, and in private contacts, Parley's own row with the same
  columns (`CustomFields`). A row keeps its kind when edited; imports, restores and folder sync give each field the kind
  of the account it lands in. vCard: `itemN.X-PARLEY-CUSTOM:<value>` with `itemN.X-ABLabel:<label>`, the grouped-item
  form Apple and Parley already use for labels. The page lists them under **More**; Contacts search finds them
  ("Matched: custom field").
- **Language** (chip "Language", one): typed as a name or a tag ("Spanish", "pt-BR"); the line under the field says what
  is kept ("Saved as Spanish") and the save stores the BCP 47 tag (`Languages`). Parley's row; vCard `LANG` (RFC 6350,
  with TYPE and PREF), or `LANGUAGE` (RFC 9554, the card's own language) for one read from that property.
- **Name block**: **Phonetic middle name** joins the phonetic first and last names (Android's own column). A contact
  that has RFC 9554's **second surname** or **generation** shows them in the open block (after the last name and the
  suffix); others never see these lines. They are Parley's row (`Mime.NAME_PARTS`) and N's sixth and seventh components.
- **Address parts** (room, apartment, floor, street number and name, building, block, subdistrict, district, landmark,
  direction): kept in the address row's DATA11 (`AddressParts`), so they stay with their address. Shown under the
  address ("Also: Floor: 2 · Building: Torre B") and under More on the page; not edited, written only with a new row,
  so an edit of the street keeps them. ADR's components 8–18 in vCard. ez-vcard 0.12 doesn't know N's and ADR's new
  components, so `Rfc9554` scribes read and write them for every vCard Parley reads or writes.
- **Phone types**: the type menu keeps the six common types first (Mobile, Home, Work, Main, Work fax, Other), then
  **More types…** (a dialog with Android's other fourteen, the current one ticked) and Custom… (`PhoneTypes`).
- **Other calendars**: the date picker has a **Calendar** choice (Gregorian, Chinese lunar, Hebrew, Hijri) while the
  date has a year. The row keeps the Gregorian day it happened in START_DATE (every other app shows that correctly) and
  the calendar's CLDR name in DATA14 (`AltCalendar`). vCard keeps the Gregorian value and names the calendar in
  Parley's own parameter (`BDAY;X-PARLEY-CALENDAR=chinese:19900127`). It never writes `CALSCALE`: RFC 6350 §5.8 says
  that parameter gives the calendar the *value* is written in, so a reader would take 19900127 as a Chinese date. On
  import, a real `CALSCALE` other than Gregorian is read that way: a Hijri (`islamic-umalqura`) date becomes its
  Gregorian day and recurs by Hijri; a Chinese or Hebrew one (no agreed way to write a leap month, Hebrew months
  numbered from Tishri or Nisan) is kept as written in a custom field ("Birthday (hebrew calendar)") and named in the
  import report. A DATA14 value Parley doesn't know is left as it is: the date picker only writes the column when the
  user changes the Calendar choice. Parley works out the next Gregorian day from Android's ICU calendars
  (`IcuCalendars`, no library): the same month and day of that calendar, a missing leap month or Adar I falling to the
  ordinary month and a missing 30th to the 29th (`AltCalendars`). The page shows "Chinese lunar · next 17 Feb 2026",
  Birthdays sorts by it and the birthday reminder fires on it. Other apps see a yearly Gregorian date.
- **Private and temporary contacts**: every field above is sealed with the details (`ContactDetailsJson`), kept in
  drafts (`ContactDraftJson`) and carried by Make private / Make visible.
