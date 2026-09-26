# Writing for Parley

How Parley talks to people (U7, round 5). Every new string follows this guide, in all 8 languages.

## Voice

- **Warm, plain, short.** Write the way a thoughtful friend would say it: "Sam might enjoy hearing from you", not "Contact overdue: Sam (42 days)".
- **No blame, no guilt.** Never count days *against* someone, and use no red badges, streaks or scores on the home screen. Numbers go in Insights, as reach and trend, and can be hidden.
- **Say what happens, then stop.** "Blocked and declined. Undo" beats "The number has been successfully added to your block list and the call was rejected."
- **One action per message.** An empty state, banner or notification offers at most one main button.
- **Give an honest reason for a permission.** Say why it's needed and what still works without it: "Without call history: you can still call; Recents stays empty."

## Reminders and nudges

| Instead of | Write |
|---|---|
| "You haven't called Sam in 42 days!" | "Sam might enjoy hearing from you" |
| "Overdue" | "Due" (chip), or no chip at all |
| "Don't forget Ana's birthday" | "Ana's birthday is on Friday" |
| "Snooze" | "Not now" (and it never escalates) |
| "You missed 3 check-ins" | Nothing. Parley doesn't report missed reminders. |

## Empty states

- Tell "nothing here yet" apart from "no matches": "No matches for 'xyz'" gets **Clear search**, and "No favourites yet" gets **Choose favourites**.
- Say what the screen is for in one sentence, then give one action.

## Privacy wording

- Say what stays on the phone, and say it once: "Stays on this phone." Don't repeat it on every screen.
- Lock-screen notifications show only "Reminder", "Missed call" or "Follow-up". Names appear only after unlock.
- Never imply that Parley sends anything anywhere: it has no internet permission.

## Mechanics

- Sentence case for titles and buttons ("Keep in touch", not "Keep In Touch").
- Use `plurals` for any count, and `%1$s`-style numbered placeholders so translators can reorder them.
- Wrap phone numbers in `Bidi.ltr()` so they read correctly in Arabic and Urdu.
- Store dates, numbers and keys with `Locale.ROOT`. Format them for display with the user's locale.
- No hard-coded UI text (the `checkHardcodedText` task warns about it).
- German uses "Sie"; the other languages use the everyday polite form of their platform's system apps.
- Translations are machine-assisted until a native speaker reviews them. Mark reviewed files in the PR description.
