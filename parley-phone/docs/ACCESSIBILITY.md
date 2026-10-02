# Accessibility

What Parley does for people who use TalkBack, Switch Access, Voice Access, large fonts or high contrast, what was checked in the 4.7 sweep (COMPETITIVE_ANALYSIS_6 P16, WP-16), and what is left. Device steps are in [TESTING.md](TESTING.md) §28.3.

## The rules every screen follows

| Rule | How |
|---|---|
| Every control has a name | Icon-only buttons take their name from the icon's content description; rows read their headline. Names are unique on a screen, so Voice Access ("tap Ringtone") and Switch Access menus can tell them apart. |
| Toggles say their state | `Role.Switch` with a stable name and a state ("Mute, on"), never a name that changes with the state. |
| Section titles are headings | `ListSectionHeader`, `GroupHeader`, sheet titles, Settings groups, the contact page's sections and Blocking & screening's sections carry `heading()`, so TalkBack's heading navigation jumps between them. |
| Targets are at least 48 dp | Material components keep the minimum interactive size; Parley's own controls are 48 dp or more (call grid 64 dp, End call 72 dp, answer circles 80 dp, quiet actions 56 dp, filter chips 48 dp targets, keypad keys at least 66 dp tall). |
| Text grows with the font size | Lists and pages scroll; labels wrap to two lines instead of clipping; the keypad's digits grow up to 1.5× and their letters fully; the editor puts the type under the field from 130 %. Where one line is kept on purpose (a list row's name), the full text is in its description. |
| Colour is never the only signal | On hold has an icon and a different container, Mute → Muted changes the icon and word, warnings carry an icon and a filled container. |
| Contrast | 4.5:1 for text and 3:1 for icons and outlines, tested on the design tokens (below). |
| Motion | Endless animations stand still when Android's animations are off (`ParleyMotion.reducedMotion`). |

## The 4.7 sweep

### Checked and fixed

| Where | Finding | Fix |
|---|---|---|
| Call screen | Nothing was spoken when a call connected, went on hold, resumed or ended; TalkBack users had to find the status pill. | `CallStateAnnouncer` (telecom `ui/CallAnnouncer.kt`): a polite live region that says "Call connected", "On hold", "Call resumed" and "Call ended" (or why it ended) on each change. The rules are `CallAnnouncements` (core:common, unit-tested): nothing when the screen opens, and the timer is never announced. |
| Incoming call, slide to answer | Voice Access "tap Answer" didn't work: the track only offered Answer and Decline as custom actions, which Voice Access doesn't use. | The two ends of the track are named buttons for accessibility services (`serviceButton` in `IncomingControls.kt`): a name, a role and a click action, but no touch handling, so a finger still has to slide and a pocket can't answer. TalkBack and Switch Access reach them too; the track keeps its actions. Tap mode and simple mode already had named, clickable Answer and Decline buttons. |
| Blocking & screening | The foldable section titles weren't headings. | `heading()` on `CollapsibleSection`'s header. |
| "Return to call" bar | One line: at 200 % the name and time were cut short. | Wraps to two lines. |
| Design tokens | Brand primary (#2F5BD3) on `surfaceDim` (#DBD9E0) is 4.2:1. | Nothing draws on `surfaceDim` in Parley, so it's left as it is and excluded from the test with a note; every surface that text does sit on passes. |

### Checked and already fine

- **Labels**: no icon-only `IconButton` without a description (searched the code); the call grid, More sheet, keypad keys (digit, letters and long-press action), the call pill segments ("Call with SIM 1 · Vodafone", "usual") and the backspace button are named.
- **Headings**: list section headers, Recents' day headers, Contacts' letters, the contact page's foldable sections, Settings groups, sheet titles.
- **Touch targets**: no clickable modifier on anything smaller than 48 dp (searched for `size`/`height` under 48 dp next to `clickable`, `toggleable`, `selectable`); chips use Material's minimum interactive size.
- **Large fonts**: the caller scrolls on the call screen while the controls stay put; grid labels wrap; the incoming quiet actions sit in fixed columns and their labels wrap; the keypad grows its keys; the call pill keeps the full name in its description.
- **TalkBack on the call screen**: the ringing caller offers Answer, Decline, Reply, Stop ringing and Block & decline as actions; the slide hint and the line label aren't read twice.

### Contrast tests

- `ThemeContrastTokensTest` (core:ui): the brand light and dark schemes and the AMOLED (pure black) variant: every `on…` colour on its container, `onSurface`, `onSurfaceVariant`, `primary` and `error` at 4.5:1 and `outline` at 3:1 on every surface and container; AMOLED surfaces under dynamic colour; white text on the Answer green and the white icon on the Decline red.
- `ThemeContrastTest` (core:common): **dynamic colour for any wallpaper**. Material's dynamic schemes give every role a fixed *tone* of the wallpaper's tonal palettes (primary 40 in light, 80 in dark, …). Tone is CIE L*, which fixes the luminance whatever the hue, so the contrast of each pair is the same for every wallpaper: `ThemeContrast` checks the tone pairs (Android 12–13 and 14+ surfaces) instead of ten sample wallpapers.
- Existing: `CallBackdropTest` (the call screen's tint and picture scrim keep 4.5:1), `CallHueTest` and the avatar palette's inks.

## Sonic caller ID (I17)

A contact's or a label's page has **Make a ringtone for Ana**: a short tune (3–5 s) made from the name, so a ring says who is calling without a look. `CallerTune` (core:common, unit-tested) maps the name's letters to a walk on the major pentatonic scale (any two notes sound well together), picks key, tempo (120–150 bpm), rhythm and timbre (bell, marimba, music box, soft flute) from the name and the variant, plays the phrase twice ending on the home note, then rests a beat so the repeating ring reads tune · pause · tune. Additive synthesis under ADSR envelopes, mixed and scaled so the loudest sample is 85 % of full scale. Deterministic (StrictMath and a seeded `Random`), so "Tune 2" is the same tune on every phone. **Try another** plays the next variant at once.

The chosen tune is written once as a 16-bit mono WAV at 22.05 kHz (about 200 KB) to `files/tunes/`, named by a hash (never the name), and served by Parley's own FileProvider:
- **Device contacts**: `Contacts.CUSTOM_RINGTONE` gets the content URI. Telecom plays it; Telecom runs as the system user, which may open any provider. If Telecom's own player fails, Android hands the tone to System UI's ringtone player, which gets a read grant (renewed when Parley starts, since grants end with a reboot). If a phone still can't open it, it falls back to the default ringtone (TESTING §28.3 step 2 checks this).
- **Private contacts** (the caller-ID copy's `rt`) and **labels**: Parley's own ringer plays it from its own files.

No new permission; nothing leaves the phone. Left out: OGG output (WAV needs no encoder and every ringer reads it), and recreating tune files after a restore on a new phone (Parley's backup carries the contact's ringtone URI but not the file; the contact then rings with the default tone until a tune is made again).

## Left for later

- Automated Compose accessibility checks (`AccessibilityChecks` in Espresso) need instrumented tests, which Parley doesn't run in CI yet.
- Captions or transcripts of calls need the microphone (out of scope); RTT is WP-14.
