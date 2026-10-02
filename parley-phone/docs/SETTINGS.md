# Settings map

Where every setting lives, by page and group. The keys in `code` are the stable keys of `SettingsCatalog` (core/common). Settings search uses them, and so do links that open a page scrolled to a setting (`Routes.settingsPage(category, key)`). No stored setting changed in this reorganisation: only the places and a few titles moved (see [Changes](#changes-in-35)). Names follow [GLOSSARY.md](GLOSSARY.md).

**Search covers everything below**, including settings on screens of their own (marked ↗): their catalog entries carry a `SettingPlace`, and search opens that screen.

**Advanced** groups are folded at the end of a page. They open by themselves when search points at a setting inside.

The Settings list starts with **Tools** (the same page as ⋮ › Tools; see the glossary) and **What Parley can do**, then the categories below.

## Appearance
| Group | Settings |
|---|---|
| Theme | Theme `theme` · Pure black dark theme `amoled` · Wallpaper colours `dynamic_color` (Android 12+) |
| Language | Language `language` |
| Lists | List density `density` · Avatars `avatar_style` |
| Names | Sort and show names by `sort_names` · Second line under names `second_line` · Prefer nicknames `prefer_nickname` |
| Tips | Reset tips `reset_tips` |

## Layout & gestures (new, split from Appearance)
| Group | Settings |
|---|---|
| Navigation bar | Navigation bar `nav_tabs` · Open on `start_tab` |
| Layout | Calls layout `calls_layout` (+ keep the Keypad tab) · Favourites in Contacts `favorites_in_contacts` (+ keep the tab, Frequent row) · Tapping a call in Recents `recent_tap` · Back to separate tabs |
| Taps and swipes | Swipe actions `swipe_actions` |
| — | Simple mode `simple_mode` ↗ (in it: keypad button `simple_keypad`, ask before declining `simple_confirm_decline`, say who is calling `simple_speak`, helpers `simple_helpers`, set up another phone `simple_share`) |

## Calls
| Group | Settings |
|---|---|
| — | Default phone app `default_dialer` · Can't make Parley the default phone app? `default_dialer_help` |
| Answering and calling | Answer incoming calls by `answer_gesture` · Call screen background `call_background` · Show contact photo on the call screen `caller_photo` · Confirm before calling `confirm_call` · Vibrate on call events `call_haptics` · Ringtone for unknown callers `unknown_ringtone` |
| Missed calls and voicemail | Remind me of missed calls `missed_realert` · Voicemail `voicemail` |
| During calls | Ask before pocket calls `pocket_guard` |
| Accessibility | Answer with RTT `answer_rtt` (off) · TTY and RTT settings ↗ (Android's call accessibility page; search finds it through `answer_rtt`'s words) |
| Know who's calling | Answer automatically `auto_answer` (off; with a headset or Bluetooth, in simple mode, for chosen people and labels; after 3–15 s with a countdown and Cancel) · Vibration for callers `caller_vibration` (set on a contact's or a label's page) |
| Remember what matters | Anything to remember? after calls `memory_prompt` · Notes on the lock screen `memory_lock_screen` · Peek before calling `pre_call_peek` |
| Family safety | Helpers `call_helpers` ↗ (up to 3 people; none by default) |
| On the road | Drive profile `drive_profile` ↗ (off until a car is marked) |
| SIMs and carrier | SIMs & plan minutes `sims` ↗ (search also finds it as Plan minutes per SIM `plan_minutes`; in it: Billing increments per SIM `sim_billing`, and under Abroad: Assisted dialling abroad `assisted_dialling` (on), Suggest a local SIM abroad `local_sim_hint` (on)) · SIM & calling accounts `sim_accounts` · Call forwarding, waiting & voicemail `carrier_settings` |
| Advanced | Vibrate when a call connects `connect_haptic` · Turn the screen off at your ear `proximity_sensor` · Power button ends call `power_button_ends_call` |

**Show contact photo on the call screen** (`caller_photo`, on by default): off shows the caller's initial on their colour instead of the photo, and no call-screen picture, on the incoming and ongoing screen and in the picture-in-picture window. Each contact (private ones too) can override it in Settings for this contact › Photo on the call screen: *Default*, *Show* or *Hide*.

**Answer with RTT** (`answer_rtt`, off by default): when you answer a call on a SIM that offers RTT (real-time text), Parley asks the network to switch the call to RTT once it's connected and opens the conversation. RTT works only where the carrier supports it (mostly in the US, on 4G and Wi-Fi calling) and the other phone does too; where no SIM offers it, the row says so. Without this setting, More › Switch to RTT does the same during any call that offers it, and a request from the other person always asks first. **TTY and RTT settings** opens Android's call accessibility page (`TelecomManager.ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS`), where some phones need RTT turned on before carriers offer it. See [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#47-rtt-and-the-call-quality-diary).

**Call screen background** (`call_background`): *Caller's colour* (the default) tints the top of the call screen with the caller's avatar colour; *Plain* keeps the theme's own background. A contact's call-screen picture is set per contact and still shows with *Plain* (remove it from the contact to hide it), and a likely-spam call keeps its red warning wash with either choice. See [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#42-revisions).

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
| Birthdays and reminders | Birthdays & dates `birthdays` · Birthday reminders `birthday_reminders` · Reminder time `reminder_time` |
| Circle: keeping in touch | Keep-in-touch nudges `nudges` · Remind me before dates `date_lead` · How keep-in-touch reminders arrive `circle_delivery` · At most per week `circle_weekly_cap` · Log messages you start `log_prompts` |
| Advanced | Import from SIM card `import_sim` · Export one account to .vcf `export_account` (with several accounts) |
| In Tools ↗ | Scan QR code `scan_qr` (also the Contacts header) · Coming from another phone? `coming_from` (also onboarding's last step) |

## Recents & history
| Group | Settings |
|---|---|
| Call history | Keep full call history `archive` (with the number of calls kept) · Keep call history `retention` · Numbers kept forever `kept_forever` (while the full history is kept) · Clear call history `clear_history` · Deleted calls `history_details` ↗ (History & undo › Calls) |
| Recents | Call list layout `recents_layout` · Recents style `recents_style` · Remember the Recents filter `recents_remember_filter` (on; never Blocked or Voicemail) · Call insights `insights` · People card in Call insights `people_card` · Who usually reaches out first `first_mover` |
| Export & import | Import call history from CSV `import_calls` · Excel-friendly CSV `csv_bom` |
| Advanced | Show SIM in call history `sim_labels` |

## Messaging
| Group | Settings |
|---|---|
| — | Quick reply messages `quick_replies` · My card `my_details` |
| Messaged numbers | Messaged numbers `messaged_numbers` · Forget messaged numbers after `messaged_expiry` |

**On the road** (WP-15; see [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#47-on-the-road)):
- **Drive profile** (`drive_profile`, Calls, a screen of its own): mark one or more Bluetooth devices as your car (paired devices on Android 12+ with "Nearby devices"; the devices connected now on any version). Only while one is connected: Say who's calling (on), Answer favourites automatically and Answer people chosen for auto-answer (off; after 3–15 s, 5 by default), Silence unknown callers (off), and driving replies first in the reply sheet. Kept on this phone only (`parley_drive_profile`; a new phone pairs again).
- **Assisted dialling abroad** (`assisted_dialling`, SIMs & plan minutes › Abroad, on): while the call's SIM is in another country, a number in the home format asks "Call +44 20 … ?" with Dial as typed. Never emergency numbers, short codes or service numbers.
- **Suggest a local SIM abroad** (`local_sim_hint`, same place, on): once per trip, when the call's SIM is roaming and the other one is local there. Both are kept in `parley_roaming`.

**Family safety** (WP-8, nothing on by default; see [CALL_SCREEN_DESIGN.md](CALL_SCREEN_DESIGN.md#45-family-safety)):
- **Family safe word** (`family_safe_word`, Privacy & security): a question and answer per label, set on the label's page after the fingerprint or screen lock. The page lists the labels and whether each has one. Kept sealed on this phone only (`family_safety`, never in backups).
- **Helpers** (`call_helpers`, Calls; also in simple mode's setup as `simple_helpers`): up to 3 contacts, private ones too, that More › Add my helper calls into a call.
- **Expecting a call from your notes** (`expected_hints`, Blocking & spam): one switch each for notes and promises with a day, To call items for numbers you haven't saved, and delivery QR codes. Each is off until the first hint asks once ("Expecting a call?") and you say yes; "No thanks" keeps it off. The windows coming up are listed and can be removed.

## Privacy & security
| Group | Settings |
|---|---|
| App lock | App lock `app_lock` · Lock again after `lock_after` · Unlock with `app_lock_method` ↗ (in it: Parley PIN `parley_pin`, Duress PIN `duress_pin`, Keep private details locked `duress_lock_vault`) · Hide screen content `secure_screen` |
| Family safety | Family safe word `family_safe_word` ↗ (set on a label's page) |
| Private contacts | Hide private contacts `hide_vault` · Private call history `private_history` |
| Your data | Privacy dashboard `privacy_dashboard` · Who can see your contacts `who_can_see` · Let apps show private names `private_names` |
| Advanced | Private names in other phone apps `private_directory` · App permissions (system) `app_permissions` |

**Duress unlock** (WP-20, nothing on by default; threat model in [SECURITY_MODEL.md](SECURITY_MODEL.md#duress-unlock)):
- **Unlock with** (`app_lock_method`, with the app lock on): "Fingerprint or screen lock" (as before) or **Parley PIN** (`parley_pin`): 4–12 digits, kept as a sealed scrypt hash in `no_backup/app_pin`, never in backups (a new phone sets its own). Changing either PIN asks for the fingerprint or screen lock first. Wrong PINs: five free tries, then 30 s doubling to an hour.
- **Duress PIN** (`duress_pin`, needs a Parley PIN): a second PIN that opens Parley as usual with private contacts, their calls, Circle notes and promises, notes for calls, call notes, family safe words and Shared with hidden, until the next unlock with the Parley PIN. While it is set only a PIN opens Parley (not the fingerprint or screen lock), and a forgotten Parley PIN can't be recovered: the screen says so before it asks for the duress PIN. Shown nowhere (rows and search) during a duress session.
- **Keep private details locked** (`duress_lock_vault`, on): after the duress PIN, private contacts' details refuse to open until the Parley PIN, even right after the phone's own unlock.

## Backup & sync
| Group | Settings |
|---|---|
| Backups | Backup & restore `backup` ↗ (in it: Automatic backups `backup_automatic`, Backups to keep `backup_keep`, Restore a backup `backup_restore`, Move to a new phone `backup_move_phone`) · Remind me to back up `backup_reminder` · Sync between your phones `sync` ↗ (in it: Sync automatically `sync_auto`) · Export notes as Markdown `markdown_export` |
| Undo | History & undo `journal` ↗ · Daily snapshots (time machine) `time_machine` ↗ (History & undo › Snapshots) |

## Notifications & device
| Group | Settings |
|---|---|
| — | Notification health card · Notification settings `notification_settings` · Allow full-screen incoming calls `full_screen` · Battery optimisation `battery` · Xiaomi: lock screen & pop-up permissions `xiaomi` (Xiaomi, Redmi, POCO only) |

## About
| Group | Settings |
|---|---|
| — | Parley version `version` · Export diagnostics `diagnostics` · Keep crash reports `crash_reports` |
| In Tools ↗ | What Parley can do `what_parley_can_do` (also at the top of Settings and in the What's new card) |

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
