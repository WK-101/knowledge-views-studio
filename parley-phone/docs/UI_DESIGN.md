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
- **TalkBack.** The lane is one node, "Alphabet index", whose state is the current letter. It is adjustable (swipe up or down to move a letter) and has the actions "Next letter" and "Previous letter" in place of the drag. It steps through the letters only, not "★": the favourites are above the letters, where the index hides, so jumping there would take the control away from under TalkBack's focus. The favourites are reached by heading navigation. The bubble is hidden from TalkBack. Letter headers are headings, so heading navigation works as well.
- **Where.** Contacts (with letter headers), the "Add to contact" picker, the picker other apps open (contacts, numbers, emails, addresses) and a label's members. Each appears only for more than 30 rows, sorted by name, while nothing is typed in search. The pickers sort with the Contacts list's collation and gather each starting letter into one block (`AlphabetIndex.grouped`), so they group by the same key they sort by. In Contacts the targets count everything that leads the list, the "private details locked" card included (`AlphabetIndex.Lead`).
- **Scrolling.** The index reads the list's layout in derived states and in the layout phase (its top and height), so a fling doesn't recompose it each frame; it recomposes when it shows or hides or its letter changes.
