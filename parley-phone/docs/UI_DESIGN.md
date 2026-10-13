# UI design decisions

Decisions about shared list and screen patterns that more than one screen follows. Screen-specific designs have their own documents (CALL_SCREEN_DESIGN.md, CONTACT_PAGE_DESIGN.md, EDITOR_DESIGN.md).

## The A–Z index (6.2.2)

### What was wrong

The old rail (`FastScrollRail`, Contacts only) filled the whole height of the Contacts list on the end edge. It covered the end of the filter chips row and of My card. With favourites in Contacts (the optional combined surface from 3.3) it stayed the same and sat over the favourites and the Circle. Between drags it was all but invisible, yet it still took taps on the end of every row, including the row's call button.

### What other apps do

- **Google Contacts** (4.42, October 2024) dropped its wide, boxed scroll bar and the separate letter column for a pill-shaped thumb with Dynamic Color and no track. The letters moved into the list as headers. The thumb runs the full height of the list. ([9to5Google](https://9to5google.com/2024/10/25/google-contacts-scroll-bar/))
- **Samsung Contacts** (One UI 4 onwards) keeps an index on the right edge. When the screen is short, or with large display or font sizes, it shows dots instead of letters. Pressing a dot shows its letter, and dragging moves through them. Users found the dots-only state hard to aim at, which is why Parley's compact form keeps every other row a real letter. ([Samsung Community](https://eu.community.samsung.com/t5/other-galaxy-s-series/alphabet-column-on-right-side-gone-in-contacts-list/m-p/4794846/highlight/true); [TechYorker on compact dots](https://techyorker.com/?p=207084))
- **iOS Contacts** (UIKit's section index, `sectionIndexTitles`) draws the titles on the trailing edge, right beside the sections they belong to. The titles come from `UILocalizedIndexedCollation`, so each language gets its own index. On a short screen it drops titles and puts "•" between them. VoiceOver treats it as one adjustable element, and swiping up or down moves between letters.
- **Fossify Contacts** (from Simple Contacts) uses Reddit's IndicatorFastScroll. A 32 dp `FastScrollerView` sits at the end edge and wraps its content height, with a thumb bubble beside it. Its letters come from the contacts that are present, and a fix made it fit on small screens. ([layout](https://github.com/FossifyOrg/Contacts); [Simple Contacts commit](https://git.wbrawner.com/wbrawner/Simple-Contacts/commit/53c08d31f9b420d509ea2ca2a19497214b06d579))
- **Material 3** has no fast-scroll component or guidance. The nearest material is AndroidX's `fastScrollEnabled` in RecyclerView, plus third-party Compose scrollbars themed for M3. Parley therefore uses its own design kit (tokens, `ParleyShapes.bubble`, `ParleyMotion` specs).

Points these apps share:

- The index sits on the end edge.
- Its entries come from the list itself.
- A bubble shows the letter under the finger.
- A short screen gets a condensed index.
- Screen readers get an adjustable control, not a drag.

None of them puts the index over content that isn't alphabetical.

### Parley's design

`AlphabetIndexRail` is in core/ui, and its rules (`AlphabetIndex`, a pure function) are in core:common.

- **End edge, its own lane.** The index sits on the end edge (the left in right-to-left languages), where one thumb reaches it and where Samsung and iOS users look for it. It has its own 48 dp lane, which meets the touch-target rule. The rows beside it are inset by `AlphabetIndexDefaults.RowEndPadding`, so their call, message, ⋮ and checkbox controls end where the lane begins and are never covered. The lane takes touches only while the index shows. Once it fades out, it has no pointer handler, so the rows get their whole width back.
- **Only beside the alphabetical part.** `AlphabetIndex.placement` places the index below the first letter header, or below the pinned header once the list is scrolled into the letters. It shows only when there is room for an index at least 160 dp tall. While the chips, My card, the favourites and the Circle fill the screen, there is no index. As the letters scroll up it fades in, and it never covers what leads the list. Under the finger it stays where it is, even when "★" scrolls the favourites back on screen.
- **Entries from the list.** `AlphabetIndex.entries` uses only the sections the list has. Latin letters appear one by one (accents are already folded by `ListSections.letterOf`). "#" appears where the list puts it. Each other script keeps its list order: an alphabet such as Cyrillic or Greek shows each letter, and a script with more than 40 starting characters over the whole list (Chinese, even where its names come in short runs between Latin ones) gets 12 entries spaced evenly. "★" leads only when the favourites are in Contacts, because then it has somewhere to jump.
- **Bubble and ticks.** Dragging shows the letter large in `ParleyShapes.bubble` beside the lane. The pointed corner faces the finger, so the thumb doesn't hide it. Each new letter gives a light segment tick, and the list jumps to it. It uses spring and effects specs from `ParleyMotion`, so it is still when animations are off.
- **Compact on short screens.** When the letters would be smaller than 14 dp a row (landscape, a split screen, the keyboard up, large display size), `AlphabetIndex.compact` draws a letter, then a dot, then a letter. The first and last entries are always letters. A drag still reaches every entry, because it picks by position over the whole index. Letters are sized in dp from the row height, at most 13 dp scaled with the font (up to twice), and a row's minimum height grows with the font scale the same way: a large font gives fewer, larger letters with dots between them instead of overlapping. The bubble's letter follows the font scale.
- **Insets and layouts.** The lane pads for the safe-drawing inset on its end side (a cutout or a landscape navigation bar). The index sits inside the list's own box, so in the wide list-detail layout it is at the end of the list pane, not the window.
- **Above the floating button.** The index ends `AlphabetIndexDefaults.BottomReserve` (`ParleyFabDefaults.Reserve`, 88 dp) above the bottom of the list, on every list that has one, so it never runs under Contacts' add button or competes with it for a touch (see "Floating buttons"). The room it needs (160 dp) counts only above that area (`AlphabetIndex.usable`).
- **TalkBack.** The lane is one node, "Alphabet index", whose state is the current letter. It is adjustable (swipe up or down to move a letter) and has the actions "Next letter" and "Previous letter" in place of the drag. It steps through the letters only, not "★": the favourites are above the letters, where the index hides, so jumping there would take the control away from under TalkBack's focus. The favourites are reached by heading navigation. The bubble is hidden from TalkBack. Letter headers are headings, so heading navigation works as well.
- **Where.** Contacts (with letter headers), the "Add to contact" picker, the picker other apps open (contacts, numbers, emails, addresses) and a label's members. Each appears only for more than 30 rows, sorted by name, while nothing is typed in search. The pickers sort with the Contacts list's collation and gather each starting letter into one block (`AlphabetIndex.grouped`), so they group by the same key they sort by. In Contacts the targets count everything that leads the list, the "private details locked" card included (`AlphabetIndex.Lead`).
- **Scrolling.** The index reads the list's layout in derived states and in the layout phase (its top and height), so a fling doesn't recompose it each frame; it recomposes when it shows or hides or its letter changes.

## Floating buttons (6.7)

### What was wrong

Three screens had a floating button, each built differently. Contacts' add button was Material's expressive FAB menu
(16 dp corners, and the menu pads its button 16 dp in from the end and up from the bottom); the docked keypad's button
in Recents was a plain `FloatingActionButton`, which takes the theme's large shape (24 dp in Parley) and sat 16 dp from
the edges, then jumped up when the number actions showed; the contact page's "Log a chat or visit" was an extended FAB
with the theme's shape again. Switching between Recents and Contacts, the button changed shape and moved.

### Parley's design

- **One component.** core/ui's `ParleyFab` (and `ParleyFabMenu` for a button that opens a short menu) is the only
  floating button: 56 dp, `ParleyShapes.fab` (16 dp, `ParleyFabDefaults.CornerRadius`), the primary container colour,
  Material's elevation, the same spring in and out. The detekt rule `DesignSystemComponent` reports Material's own FABs
  outside core/ui, and `FloatingButtonsTest` scans the app for them.
- **One place.** Bottom end, `ParleyFabDefaults.Margin` (16 dp) from the end edge and from the navigation bar: the
  Scaffold's own FAB place, or the list pane's bottom end beside a detail pane (tablets, landscape). `ParleyFabMenu`
  undoes the menu's own padding so its closed button sits exactly there. Only the icon and the action change between
  tabs.
- **It never moves.** What sits along the bottom makes room for it (`ParleyFabDefaults.Reserve`): the keypad's number
  actions end before it, and lists leave space under their last row. Nothing hides it on scroll; the contact page's
  labelled button folds to its icon once the header scrolls away, at the same height and corner.

### The one rule (6.8)

On device the Contacts add button sat over the bottom of the A–Z index: both are on the end edge, and the index ran to
the bottom of the list. The fix keeps the button where it is and makes everything else give way, on every screen:

- **The button's place never changes.** Bottom end, 16 dp from the end edge and from whatever is below it (the
  navigation bar, the Situation chip, the return-to-call chip): those bars lift the whole area with them.
  The same on Contacts, Recents with the docked keypad, the contact page, with favourites in Contacts, in the
  list-detail layout on tablets and in landscape. During a selection or a search it hides; it never moves.
- **Its area is one number.** `ParleyFabDefaults.Reserve` (the 56 dp button and 16 dp above and below: 88 dp),
  measured up from the bottom of the list or pane it floats over. Nothing else edge-anchored takes that area:
  - the A–Z index ends above it (`AlphabetIndexDefaults.BottomReserve`, the same number);
  - a list under a button ends with `ParleyFabDefaults.listPadding()` (Contacts) or the same reserve added to its own
    bottom padding (the contact page, Recents and the keypad's matches over the docked keypad), so its last row scrolls
    clear of the button;
  - a snackbar sits above it: the Scaffold lifts it for its own button, and `Modifier.clearOfFab()` lifts Home's
    snackbar for a button placed by hand (the list pane's add button, the folded keypad's button);
  - a row along the bottom (the keypad's number actions) ends before it.
- **No screen keeps its own clearance number.** `FloatingButtonsTest` checks that the reserve is the button and its
  margins, that the index and every list under a button use it, and that no screen with a button has a hand-made
  bottom clearance (72 dp or more) instead.

## Moving between pages (7.0)

- **One source of motion.** Page changes, swaps and expanding sections all read `ParleyMotion`, which reads the theme's expressive `MotionScheme`. Spatial springs move and scale things; effects specs fade them (they never overshoot, so alpha never "bounces").
- **Pages.** Forward: shared axis, the new page sliding 30 dp in from the end side (left in right-to-left languages) as the old one drifts toward the start and fades. Back: the leaving page scales to 90 % and fades, and the page beneath grows back from 95 %. The navigation host's predictive back scrubs these with the gesture, so the swipe previews the page beneath. The specs are read in composition (`navEnter`/`navExit`/`navPopEnter`/`navPopExit`) because the host's transition lambdas aren't composable.
- **Swaps.** `swap()` (a fade) for one label or bar replacing another; `swapScaled()` for one panel of controls replacing another (the call screen's ringing, talking and ended controls, and its keypad); `fadeThrough()` for top-level places.
- **Back and open once.** Every graph takes `nav.parleyBack()` and `nav.parleyOpen()`. A Back that lands on a page is ignored until that page has settled, and Home is never popped, so a fast double tap can't leave an empty host. A second open of the same page while it still slides in is dropped.
- **Windows.** Every activity starts with `setParleyContent` (edge to edge before the first frame) and themes itself with `ParleyTheme(settings.themeLook)`.
