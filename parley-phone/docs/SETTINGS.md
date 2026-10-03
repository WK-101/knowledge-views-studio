# Settings map

Where every setting lives, by page and group. The keys in `code` are the stable keys of `SettingsCatalog` (core/common). Settings search uses them, and so do links that open a page scrolled to a setting (`Routes.settingsPage(category, key)`). No stored setting changed in this reorganisation: only the places and a few titles moved (see [Changes](#changes-in-35)). Names follow [GLOSSARY.md](GLOSSARY.md).

**Search covers everything below**, including settings on screens of their own (marked ↗): their catalog entries carry a `SettingPlace`, and search opens that screen.

**Advanced** groups are folded at the end of a page. They open by themselves when search points at a setting inside.

The Settings list starts with **Tools** (the one hub, the same page as every tab's ⋮ › Tools; see the glossary), then the categories below. **Reminders** ↗ is a page of its own for every kind of reminder, linked from Calls, Contacts, Recents & history and Backup & sync (see [Reminders](#reminders-)).

## Appearance
| Group | Settings |
|---|---|
| Theme | Theme `theme` · Pure black dark theme `amoled` · Wallpaper colours `dynamic_color` (Android 12+) |
| Language | Language `language` |
| Lists | List density `density` · Avatars `avatar_style` |
| Names | Sort by `sort_names` (First name) · Show names as `name_order` (First name first) · Second line under names `second_line` · Prefer nicknames `prefer_nickname` |
| Tips | Reset tips `reset_tips` |

## Layout & gestures (new, split from Appearance)
| Group | Settings |
|---|---|
| Navigation bar | Navigation bar `nav_tabs` · Open on `start_tab` |
| Layout | Calls layout `calls_layout` (+ keep the Keypad tab) · Favourites in Contacts `favorites_in_contacts` (+ keep the tab, Show Frequent) · Tapping a call in Recents `recent_tap` · Back to separate tabs |
| Taps and swipes | Swipe actions `swipe_actions` |
| — | Simple mode `simple_mode` ↗ (in it: keypad button `simple_keypad`, ask before declining `simple_confirm_decline`, say who is calling `simple_speak`, helpers `simple_helpers`, set up another phone `simple_share`) |

## Calls
Calls is a short list: the default phone app, one row for each of its four pages, and the rows used most. Search opens a page scrolled to the setting (`CallsRoutes.Page`).

| Group | Settings |
|---|---|
| — | Default phone app `default_dialer` · Can't make Parley the default phone app? `default_dialer_help` |
| — | Answering ↗ · During calls ↗ · SIMs & carrier ↗ · Situations ↗ (the pages below) |
| Missed calls and voicemail | Reminders `reminders` ↗ (with Remind me of missed calls `missed_realert`) · Voicemail `voicemail` |
| Before you call | Confirm before calling `confirm_call` · Ask before pocket calls `pocket_guard` |

### Calls › Answering (`SettingPlace.CALLS_ANSWERING`)
| Group | Settings |
|---|---|
| Incoming calls | Answer incoming calls by `answer_gesture` · Ringtone for unknown callers `unknown_ringtone` · Call screen background `call_background` · Show contact photo on the call screen `caller_photo` · Flip to silence `flip_to_silence` (off; turning the phone face down while it rings stops the sound, never declines) |
| Know who's calling | Answer automatically `auto_answer` (off; with a headset or Bluetooth, in simple mode, for chosen people and labels; after 3–15 s with a countdown and Cancel) · Vibration for callers `caller_vibration` (set on a contact's or a label's page) |
| Accessibility | Answer with RTT `answer_rtt` (off) · TTY and RTT settings ↗ (Android's call accessibility page; search finds it through `answer_rtt`'s words) |

### Calls › During calls (`SettingPlace.CALLS_DURING`)
| Group | Settings |
|---|---|
| Speaker | Start calls on speaker `speaker_default` (Never, the default · Always · Numbers not in your contacts; only instead of the earpiece, never for emergency calls) |
| Vibration and screen | Vibrate on call events `call_haptics` · Vibrate when a call connects `connect_haptic` · Turn the screen off at your ear `proximity_sensor` (Off · During calls, the default · Once answered) · Power button ends call `power_button_ends_call` |
| Remember what matters | Anything to remember? after calls `memory_prompt` · Notes on the lock screen `memory_lock_screen` · Peek before calling `pre_call_peek` |

### Calls › SIMs & carrier (`SettingPlace.CALLS_SIMS`)
| Group | Settings |
|---|---|
| — | SIMs & plan minutes `sims` ↗ (search also finds it as Plan minutes per SIM `plan_minutes`; in it: Billing increments per SIM `sim_billing`, and under Abroad: Assisted dialling abroad `assisted_dialling` (on), Suggest a local SIM abroad `local_sim_hint` (on)) · SIM & calling accounts `sim_accounts` · Call forwarding, waiting & voicemail `carrier_settings` |

### Calls › Situations
Screens of their own for particular calls; search opens each screen directly.

| Group | Settings |
|---|---|
| Family safety | Helpers `call_helpers` ↗ (up to 3 people; none by default) |
| On the road | Drive profile `drive_profile` ↗ (off until a car is marked) |
| — | Phone menus `phone_menus` ↗ (in it: Remember menu keys `menu_memory`) · Reminders & limits `call_time` ↗ (also on the Call time page) |

**Show contact photo on the call screen** (`caller_photo`, on by default): off shows the caller's initial on their colour instead of the photo, and no call-screen picture, on the incoming and ongoing screen and in the picture-in-picture window. Each contact (private ones too) can override it in Settings for this contact › Photo on the call screen: *Default*, *Show* or *Hide*.

**Answer with RTT** (`answer_rtt`, off by default): when you answer a call on a SIM that offers RTT (real-time text), Parley asks the network to switch the call to RTT once it's connected and opens the conversation. RTT works only where the carrier supports it (mostly in the US, on 4G and Wi-Fi calling) and the other phone does too; where no SIM offers it, the row says so. Without this setting, More › Switch to RTT does the same during any call that offers it, and a request from the other person always asks first. **TTY and RTT settings** opens Android's call accessibility page (`TelecomManager.ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS`), where some phones need RTT turned on before carriers offer it. See [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#47-rtt-and-the-call-quality-diary).

**Call screen background** (`call_background`): *Caller's colour* (the default) tints the top of the call screen with the caller's avatar colour; *Plain* keeps the theme's own background; *Poster* is like *Caller's colour*, but a contact's call-screen picture fills the screen as a poster with the name set large over it, low above the controls (one-column layout only; classic in landscape, two panes, with the keypad open and during call waiting; never over a spam warning or on a masked lock screen). A contact's call-screen picture is set per contact and still shows with *Plain* (remove it from the contact to hide it), and a likely-spam call keeps its red warning wash with either choice. See [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#42-revisions).

## Keypad
| Group | Settings |
|---|---|
| Feedback | Keypad tones `keypad_tones` · Keypad vibration `keypad_vibration` |
| Keys | Keypad letters `keypad_letters` · Speed dial `speed_dial` ↗ · USSD replies `ussd` |

## Call time
| Group | Settings |
|---|---|
| — | Reminders & limits `call_time` ↗ (in it: Talk-time reminders `ct_reminders`, Call time limits `ct_limits`, Supervised mode `ct_supervised`) · SIMs & plan minutes `sims` |

## Blocking & spam
| Group | Settings |
|---|---|
| — | Blocking & screening `blocking` ↗ · Let repeat callers through `repeat_callers` · Learn from your calls `learn_from_calls` (on: quiet tags only) · Silence numbers that look like sales lines (your calls) `silence_sales_lines` (off) · Expecting a call `expecting_call` · Expecting a call from your notes `expected_hints` (off until accepted) |
| Lists and rules | Spam lists `spam_lists` · Rule templates `templates` · Test a call `dry_run` · Import & share rules `transfer` |
| On Blocking & screening ↗ | Silence or block hidden numbers `blk_hidden_numbers` · Only people I know ring `blk_non_contacts` · Off hours `blk_off_hours` · More checks `blk_more_checks` · Sounds for screened calls `blk_sounds` · Emergency numbers `blk_emergency` · Blocked call notifications `blk_notifications` · Blocked numbers (system list) `blk_system_list` |

## Contacts
| Group | Settings |
|---|---|
| Contact list | Call and message buttons in the list `row_actions` (off by default) |
| Organise | Save new contacts to `default_account` · Labels `labels` · Add relations to both contacts `mirror_relations` (on by default) · Temporary contacts `temporary_contacts` · Add several numbers `bulk_add` · Find & merge duplicates `duplicates` · Contact health check `health` · Contact page sections `contact_page` ↗ (in it: Jump to a section `section_chips`) |
| Import and export | Import from .vcf or .csv file `import_file` · Export all to .vcf file `export_vcf` · Export all to .csv file `export_csv` |
| Birthdays and reminders | Birthdays & dates `birthdays` · Reminders `reminders` ↗ (birthday and keep-in-touch reminders are there) |
| Circle: keeping in touch | Log messages you start `log_prompts` · Keep-in-touch reminders ↗ (Reminders) |
| Advanced | Import from SIM card `import_sim` · Export one account to .vcf `export_account` (with several accounts) |
| In Tools ↗ | Scan QR code `scan_qr` (also the Contacts header) · Coming from another phone? `coming_from` (also onboarding's last step) |

## Recents & history
| Group | Settings |
|---|---|
| Call history | Keep full call history `archive` (with the number of calls kept) · Keep call history `retention` · Numbers kept forever `kept_forever` (while the full history is kept) · Clear call history `clear_history` · Deleted calls `history_details` ↗ (History & undo › Calls) |
| Recents | Call list layout `recents_layout` · Recents style `recents_style` (Rich; also Simple, or Cards: each day in a rounded card) · Remember the Recents filter `recents_remember_filter` (on; never Blocked or Voicemail) · Reminders `reminders` ↗ · Call insights `insights` · People card in Call insights `people_card` · Who usually reaches out first `first_mover` |
| Export & import | Import call history from CSV `import_calls` · Excel-friendly CSV `csv_bom` |
| Advanced | Show SIM in call history `sim_labels` |

## Messaging
| Group | Settings |
|---|---|
| — | Quick reply messages `quick_replies` (with the "Text me your name" reply for numbers not in your contacts, its own field; empty turns it off) · My card `my_details` |
| Messaged numbers | Messaged numbers `messaged_numbers` · Forget messaged numbers after `messaged_expiry` |

**On the road** (WP-15; see [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#47-on-the-road)):
- **Drive profile** (`drive_profile`, Calls › Situations, a screen of its own): mark one or more Bluetooth devices as your car (paired devices on Android 12+ with "Nearby devices"; the devices connected now on any version). Only while one is connected: Say who's calling (on), Answer favourites automatically and Answer people chosen for auto-answer (off; after 3–15 s, 5 by default), Silence unknown callers (off), and driving replies first in the reply sheet. Kept on this phone only (`parley_drive_profile`; a new phone pairs again).
- **Assisted dialling abroad** (`assisted_dialling`, SIMs & plan minutes › Abroad, on): while the call's SIM is in another country, a number in the home format asks "Call +44 20 … ?" with Dial as typed. Never emergency numbers, short codes or service numbers.
- **Suggest a local SIM abroad** (`local_sim_hint`, same place, on): once per trip, when the call's SIM is roaming and the other one is local there. Both are kept in `parley_roaming`.

**Family safety** (WP-8, nothing on by default; see [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#45-family-safety)):
- **Family safe word** (`family_safe_word`, Privacy & security): a question and answer per label, set on the label's page after the fingerprint or screen lock. The page lists the labels and whether each has one. Kept sealed on this phone only (`family_safety`, never in backups).
- **Helpers** (`call_helpers`, Calls › Situations; also in simple mode's setup as `simple_helpers`): up to 3 contacts, private ones too, that More › Add my helper calls into a call.
- **Expecting a call from your notes** (`expected_hints`, Blocking & spam): one switch each for notes and promises with a day, To call items for numbers you haven't saved, and delivery QR codes. Each is off until the first hint asks once ("Expecting a call?") and you say yes; "No thanks" keeps it off. The windows coming up are listed and can be removed.

## Privacy & security
| Group | Settings |
|---|---|
| App lock | App lock `app_lock` · Lock again after `lock_after` · Unlock with `app_lock_method` ↗ (in it: Parley PIN `parley_pin`, Duress PIN `duress_pin`, Keep private details locked `duress_lock_vault`) · Hide screen content `secure_screen` |
| Lock screen | Caller on the lock screen `lock_screen_caller` (Name; Name and notes, Initials, Just "Incoming call") |
| Family safety | Family safe word `family_safe_word` ↗ (set on a label's page) |
| Private contacts | Hide private contacts `hide_vault` · Private call history `private_history` |
| Your data | Privacy dashboard `privacy_dashboard` · Who can see your contacts `who_can_see` · Let apps show private names `private_names` |
| Advanced | Private names in other phone apps `private_directory` · App permissions (system) `app_permissions` |

**Caller on the lock screen** (`lock_screen_caller`, *Name* by default): what the incoming and ongoing call notifications and the call screen show about the caller while the phone is locked. *Name and notes* shows the name with the pinned note for calls, "Who is this?" and the last call (what *Name* showed before 5.4). *Name* shows the name, but the note, "Who is this?" and the last call wait until you unlock. *Initials* shows only the initials of a saved name ("AL"), with no photo, number, label, pronouns, notes or subject, and none of the lines that could name them: the rule or label a call rang through by, a limit named after them, the time where they are. An unknown number still shows its number, also when the network sends a name with it. *Just "Incoming call"* shows nothing about who it is ("Ongoing call" once answered). Conference participants are masked one by one, and "Speak caller's name" stays quiet while the name is hidden. A screening warning ("Likely spam") still shows: it is about safety, not about who it is. Once you unlock, everything shows again. With Initials or Just "Incoming call" the notifications are also marked private, so a lock screen set to hide sensitive content shows the same short version. Emergency calls always show in full. Private contacts and discreet mode can only hide more: this setting never brings back a name they hide.

**Duress unlock** (WP-20, nothing on by default; threat model in [SECURITY_MODEL.md](SECURITY_MODEL.md#duress-unlock)):
- **Unlock with** (`app_lock_method`, with the app lock on): "Fingerprint or screen lock" (as before) or **Parley PIN** (`parley_pin`): 4–12 digits, kept as a sealed scrypt hash in `no_backup/app_pin`, never in backups (a new phone sets its own). Changing either PIN asks for the fingerprint or screen lock first. Wrong PINs: five free tries, then 30 s doubling to an hour.
- **Duress PIN** (`duress_pin`, needs a Parley PIN): a second PIN that opens Parley as usual with private contacts, their calls, Circle notes and promises, notes for calls, call notes, family safe words and Shared with hidden, until the next unlock with the Parley PIN. While it is set only a PIN opens Parley (not the fingerprint or screen lock), and a forgotten Parley PIN can't be recovered: the screen says so before it asks for the duress PIN. Shown nowhere (rows and search) during a duress session.
- **Keep private details locked** (`duress_lock_vault`, on): after the duress PIN, private contacts' details refuse to open until the Parley PIN, even right after the phone's own unlock.

## Backup & sync
| Group | Settings |
|---|---|
| Backups | Backup & restore `backup` ↗ (in it: Automatic backups `backup_automatic`, Backups to keep `backup_keep`, Restore a backup `backup_restore`, Move to a new phone `backup_move_phone`) · Reminders `reminders` ↗ (with Remind me to back up `backup_reminder`) · Sync between your phones `sync` ↗ (in it: Sync automatically `sync_auto`, and Shared labels `shared_labels` ↗ with Join a shared label `shared_labels_join`) · Export notes as Markdown `markdown_export` |
| Undo | History & undo `journal` ↗ · Daily snapshots `time_machine` ↗ (History & undo › Snapshots) |

**Shared labels** (`shared_labels`, a screen of its own reached from Sync between your phones, and searchable as "family phonebook"; nothing is shared until you choose a label's ⋮ › Share this label…): every label shared with other people's phones, each with its own folder and passphrase, and **Join a shared label** (`shared_labels_join`) from an invitation file or a QR code. The label page shows each shared label's members, changes ("Ana changed Dr Lee's number · 2 days ago") and contacts changed on two phones. Runs with the folder sync's schedule (shortly after start, after a change to the address book, hourly), whether or not "Sync between your phones" is set up. Kept on this phone only, sealed (`no_backup/shared_labels`: a new phone joins again with an invitation). See [SHARED_LABELS.md](SHARED_LABELS.md).

## Notifications & device
| Group | Settings |
|---|---|
| — | Notification health card · Notification settings `notification_settings` · Allow full-screen incoming calls `full_screen` · Battery optimisation `battery` · Xiaomi: lock screen & pop-up permissions `xiaomi` (Xiaomi, Redmi, POCO only) |

## Reminders ↗
Every reminder Parley sends, on one page (`SettingPlace.REMINDERS`), each with its switch and time. The settings are stored where they always were; only the page is new. Search opens it scrolled to the row, and an old link to one of these rows on its category page (a restored back stack) opens it too. Most of their notification channels share one channel group, **Reminders**, with the same channel ids as before, so sound and importance choices stay. Circle ⋮ › Circle settings opens Contacts › Circle: its own setting, and a link here.

| Group | Settings |
|---|---|
| Missed calls | Remind me of missed calls `missed_realert` (off) |
| To call and follow-ups | To call `to_call` ↗ (each item has its own time) · Anything to remember? after calls `memory_prompt` (also under Calls, with the other note settings) |
| Keep in touch | Keep-in-touch nudges `nudges` · How keep-in-touch reminders arrive `circle_delivery` (weekly digest by default) · At most per week `circle_weekly_cap` (one at a time only) |
| Birthdays and dates | Birthday reminders `birthday_reminders` · Reminder time `reminder_time` · Remind me before dates `date_lead` |
| Backups | Remind me to back up `backup_reminder` |
| Temporary contacts | Ask before deleting temporary contacts `temp_ask_first` (also on the Temporary contacts screen) |
| Notifications | Reminder notifications (Android's notification settings for Parley) |

Channel group **Reminders**: Birthdays, keep in touch & follow-ups (`reminders_v1`), To call (`to_call_v1`) and Backup reminders (`backup_reminder_v1`). Three kinds stay outside it, because their channels also carry notices that aren't reminders and turning the group off must never hide those: missed calls (their own channel, with the calls), due temporary contacts (Contacts housekeeping) and backup results (`backup_v1`, Backups: a scheduled backup that failed, or rotation paused). The backup reminder used to share `backup_v1`; its own channel starts no louder than Backups was set, so someone who had turned Backups off doesn't start getting reminders.

## About
| Group | Settings |
|---|---|
| — | Parley version `version` · Export diagnostics `diagnostics` · Keep crash reports `crash_reports` |
| In Tools ↗ | Tools `what_parley_can_do` (the hub itself, at the top of Settings; search finds it as "What Parley can do" too) |

## Changes in 5.1

- **One hub.** Tools and "What Parley can do" are one page, **Tools**: every feature grouped by the job you want done, the most used rows of each job shown first and the rest under "n more", with search. Lock now, Expecting a call (a switch), Import & export contacts and the other former Tools rows are in it. The Settings list has one **Tools** row (it had Tools and What Parley can do). The Privacy dashboard lives under Settings › Privacy & security › Your data and stays in the hub's "Keep it private" job. Old links to the Tools page open the hub. **Who's in…** left the ⋮ menus: it is the Contacts search's city chip and a row in "Stay in touch", and Settings search finds Tools by "who's in", "trip" or "travel".
- **One Reminders page.** Remind me of missed calls (from Calls), Birthday reminders, Reminder time, Remind me before dates, Keep-in-touch nudges, How keep-in-touch reminders arrive and At most per week (from Contacts) and Remind me to back up (from Backup & sync) moved to **Reminders**, which also lists To call, the after-call prompt and Ask before deleting temporary contacts. Contacts, Recents & history, Calls and Backup & sync link to it. Nothing stored changed, and search finds each by its old words.
- **Calls has pages of its own.** Its 31 entries were the most of any page. The Calls page now shows the default phone app, four pages (Answering, During calls, SIMs & carrier, Situations), missed calls and voicemail, and Before you call. Family safety's helpers, the drive profile, phone menus and call time are together on Situations. No stored setting changed; search finds every setting by its old words and opens the page it is on now.
- **Sort by and Show names as are two settings**, as in Android's own Contacts. "Sort and show names by" did both: it is now **Sort by** (`sort_names`, the list order, letter headers and the A–Z index) and **Show names as** (`name_order`, how names read in lists, search, Recents and on the call screen). A phone that never chose Show names as keeps showing names the way it sorts them: the setting is stored only once it differs from what Sort by gives, and restoring a backup made before it existed (Sort by only) shows names the way that backup sorted them. Private contacts follow both settings too, by their family name (from the name's parts; for one saved before those were kept, the last word of the name). Search finds both by "sort and show names by".
- **Settings budget.** `SettingsSearchTest` records a ceiling for the number of settings and a limit of 22 searchable rows per page. A new setting replaces one or folds into one; one moved onto a screen of its own still counts. Links to a page or list that hold no value of their own (Reminders `reminders`, To call `to_call`, marked `link` in `SettingsCatalog`) are searchable but not counted. 5.1 grew by one setting, from 161 to 162: **Show names as** (`name_order`), which the plan asked for by splitting "Sort and show names by" in two, as Android's Contacts does. Nothing else was added; the ceiling was 162. 5.3 grew by two, to 164, both approved by the owner with the plan (COMPETITIVE_ANALYSIS_7 §8.5 E1, E2): **Start calls on speaker** (`speaker_default`, one choice rather than separate switches for "always", "unknown numbers" and "no headset": a headset or car always wins, so "no headset" needs no choice of its own) and **Flip to silence** (`flip_to_silence`). "Proximity only after answering" folded into **Turn the screen off at your ear**, which became a choice (Off · During calls · Once answered) instead of a switch, and the "Text me your name" reply is a field of **Quick reply messages**, so neither counts.

## Changes in 4.1

- **Call and message buttons in the list** moved from Layout & gestures to Settings › Contacts › Contact list, where people look for how the list looks. The setting and its default (off) are unchanged; search finds it under both names.

## Changes in 3.5

- **Appearance was split.** Navigation bar, Open on, the combined layouts, Tapping a call in Recents, the row buttons, Swipe actions and Simple mode moved to the new **Layout & gestures** page. Theme, language, lists and names stay in Appearance.
- **The "Call history" sub-screen is folded into Recents & history.** "Keep full call history" is shown once (with the count of kept calls), and so are Import and the retention. The kept-forever numbers and "Excel-friendly CSV" are rows of the page. Deleted calls are restored in **History & undo › Calls**. The old route (`settings/history`, used by the archive notices) opens the page.
- **One row for SIMs and plan minutes.** "SIMs" is now "SIMs & plan minutes". Call time links to the same row instead of a second "Plan minutes per SIM" row. Search still finds "plan minutes".
- **Advanced groups** hold the rarely needed switches: the connect buzz, the proximity sensor, Power button ends call, Import from SIM, Export one account, Show SIM in call history, Private names in other phone apps and App permissions.
- **Circle settings have their own group** in Contacts, instead of sitting under Birthdays.
- **Scan QR code** moved from Settings › Contacts to Tools (and stays in the Contacts header).
- **Undo** is one place: "Recently deleted & changed" is **History & undo**, and "What changed" is its **Snapshots** tab.
- **Settings search** also covers the settings on Blocking & screening, Simple mode, Call time, Backup & restore, Sync, Contact page sections and the SIM screen (23 new entries).
