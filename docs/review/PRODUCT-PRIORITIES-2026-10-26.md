# BookCover: product priorities and Codex brief

Written 2026-10-26 by Claude (Opus) at the owner's request. Assessed source: `main` at
`474b0e1954ffff07cc34b9712d962e4657959141` ("Tracker: BC-27 owner confirmed, done").

This is a planning document. It does not change the Completion tracker in
`EXPERT-PANEL-REVIEW.md` or anything in `docs/coordination/`. When work starts on an item
here, claim it through the normal tracker and coordination process. Re-check the evidence
first, because the code may have moved on since this commit.

Tracker links: C2 relates to BC-07 and BC-15, S1 to BC-22, H3 and S2 to the Stage 3 UX
items (BC-23 follow-up), and S3 to BC-19. C1 is a new defect with no tracker ID yet.

## 1. Current product assessment

### What was reviewed

- **Code.** The exact source of `main` at the commit above: 37 Kotlin files, about 3,800 lines.
- **Docs.** `CLAUDE.md`, `docs/coordination/README.md`, the Completion tracker and UI/UX
  sections of `docs/review/EXPERT-PANEL-REVIEW.md`, and `docs/review/KNOWN-LIMITATIONS.md`.
- **Screenshots.** `output/BookCover-library.png` and `output/BookCover-reader-epub.png`.
  Both date from 0.3.0 and are older than the current build.

### Limitations

- No build or test run, and the outside cover was not observed.
- No new screenshots were taken. Using adb would have meant driving the owner's phone
  outside the PHONE reservation protocol, and `CLAUDE.md` forbids screenshotting the
  owner's personal books, which fill the library screen.
- None of the six priorities depends on screenshots. C1 would not show on the owner's
  phone anyway, because that phone already has saved text settings.

### Maturity

This is a well-built, heavily tested prototype: 138 JVM tests, 68/68 on-phone QA tests,
and careful data-safety work. The reading engine is solid. Positions save reliably and
books reopen where the reader left off. Bookmarks and Contents work, and gesture and
keyboard handling are careful.

### Strongest qualities

- The library and reading positions are not lost.
- Accessibility basics are in place.
- The theme taken from the book cover is a charming touch.
- Typography reflow keeps the reading position.

### Biggest obstacles

- **The reading controls are still prototype-grade.** They are one long scrolling panel.
- **Folding or unfolding the phone reloads the book.** It also ends the outside cover,
  which is the app's defining feature.
- **A new user cannot change the text size.** This is a real defect that the owner's
  phone hides.
- **Coordination overhead is high.** The PM, reservation and handoff documents now take
  up a large share of the work. This does not affect users, but it slows down polish work.

## 2. Critical improvements

The evidence supports **two genuinely critical issues**. The third is labelled **high
priority**, not a release blocker.

### C1 (Critical): Text size and fonts are locked on every fresh install

**Problem and evidence (from the code, high confidence):**

- `reader/ReadingComfort.kt:21,33` turns "Publisher styles" **on** for any install that
  has no saved text settings.
- `reader/EpubReaderScreen.kt:161` (`typeEnabled = canChangeType && !publisherStyles`)
  then disables A−/A+, the size slider, fonts, spacing, margins and alignment.
- `reader/ReadingComfortPreferences.kt:29` also forces `fontSize = 1.0` while publisher
  styles are on, so a saved text size is silently ignored.
- **The owner never saw this because their phone already had saved text settings, so it
  kept custom typography on upgrade.** Anyone installing fresh hits it.

**Why it matters:** Text size is the first thing most people adjust in a reader. At the
moment the only way to change it is to find a toggle that also throws away the book's own
formatting.

**Recommended change:**

- Make text size independent of publisher styles. Always send `fontSize = fontScale`;
  Readium applies size whether or not book formatting is on.
- Keep A−/A+ and the slider enabled whenever the book is reflowable (not fixed-layout).
- Font family: also send it while publisher styles are on, if a check on a test book
  confirms Readium applies it. If not, leave fonts with the other custom settings.
- Keep line spacing, margins and alignment behind the publisher-styles toggle, and
  relabel that section "Custom formatting (replaces the book's)".
- Update the JavaScript check in `applyTypography()` that confirms settings were applied
  (around `reader/EpubSession.kt:325–333`); it currently assumes size is 100% whenever
  book formatting is on.
- Update `ReadingComfortTest`.

**Acceptance criteria:**

1. On a fresh install of the QA app, A+ is enabled and grows the text while publisher
   styles stay on.
2. The poetry test book keeps its indents at 150% text size.
3. A saved 140% size survives turning publisher styles off and on, and closing and
   reopening the book.

**Effort and dependencies:** Small. No prerequisites. The risk is how Readium handles font
family with publisher styles on; check it on the `EpubCorpus` books.

### C2 (Critical): Folding or unfolding reloads the book and kills the outside cover

**Problem and evidence:**

- The tracker lists BC-07 as **Partial**: "Still reloads the book and ends the cover
  session."
- In the code, the manifest has no `configChanges`. `MainActivity` calls
  `super.onCreate(null)` and reopens the book from scratch.
  `ExteriorCoverController.close()` stops the cover with `"host activity
  destroyed/recreated"` (`cover/ExteriorCoverController.kt:169`).
- The cover then restarts through `autoCover`, and Android's display prompt may appear
  again ("Accept Android's display dialog if offered").

**Why it matters:**

- People fold and unfold a foldable many times a day.
- Each time, the reader gets a reload pause, possibly a system prompt, and the feature the
  app is named after going off and on.
- That undermines trust in the product's identity.

**Recommended change, in two steps:**

1. **Quick fix, now.** Add
   `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|density|keyboardHidden"`
   to `MainActivity`, so the reader resizes in place instead of being rebuilt.
   - Before the size changes, record the visible reading anchor. After layout settles, go
     back to it, reusing the anchor logic from `applyTypography()`.
   - The existing `layoutListener` in `EpubSession` already refreshes chapter info when the
     size changes.
   - When the phone is unfolded, restart the cover only if it was on before the fold.
2. **Durable fix, later.** Move the open session and import state into a ViewModel
   (BC-15), so the next unavoidable rebuild also does not reload.

**Acceptance criteria:**

1. Folding and unfolding 10 times while reading never shows the library or a blank page.
   The visible paragraph is still on screen each time.
2. With the outside cover on, unfolding brings the cover back without the user opening the
   controls.
3. `ReaderLifecycleTest` passes, plus a new fold-in-place test in the QA app.

**Effort and dependencies:** Medium for step 1; large including the ViewModel. The risk is
Readium's WebView re-paginating after a change in screen density between the two displays.
That must be tested on the real phone, folded and unfolded. Only the owner can confirm the
cover by looking at the phone.

### H3 (High priority): The reading controls cover the page being adjusted

**Problem and evidence (from the code):**

- `reader/EpubReaderScreen.kt:113–240` is one scrolling panel up to **75% of the screen
  height** (line 114).
- It stacks the title (shown twice), chapter progress, cover status, a raw status line
  (`message + "\n" + exterior.status`, `MainActivity.kt:132`), and Start/Stop cover.
- Then Contents and Bookmark, **Previous/Next page buttons**, and the whole Text section:
  toggle, A−/A+, slider, 3 fonts, spacing, margins and alignment.
- Then Page turning with a keyboard explainer, Page colour, Choose cover, and "Resume
  reading" (line 239), which duplicates the top bar's "Read" (line 110).
- BC-23 only added section headers; the panel's shape is the same. The expert panel
  flagged this, and it is still true.

**Why it matters:**

- People change text size and colour while looking at the text. Here, the panel hides the
  very text being adjusted.
- Status text that is really diagnostics leaks into reading.
- Everything gets the same visual weight.

**Recommended change:**

- Replace the panel with a **slim bottom bar of 5 labelled icon buttons**: Contents · Aa ·
  Bookmark · Cover (on/off state chip) · More.
- **Aa opens a short sheet** (at most about 40% of the height, anchored to the bottom)
  containing:
  - text size and font, with page colour shown as three swatches at the top;
  - spacing, margins and alignment in a collapsible "Custom formatting" group.
- **More** holds page-turning options and "Choose cover image".
- Remove the Previous/Next page buttons. Swipes, edge taps and keys already cover them. If
  accessibility needs a fallback, keep it as screen-reader actions.
- Remove "Resume reading" and the raw status line. Cover status lives only on the Cover
  chip; errors go to a short message banner (snackbar).
- Keep every existing test tag and accessibility label.

**Acceptance criteria:**

1. With Aa open on the inner screen, at least 55% of the page text stays visible.
2. Text size, page colour and bookmark are each reachable in two taps.
3. All QA and accessibility tests pass at 200% system font size.

**Effort and dependencies:** Medium. Do it after C1, since both touch the same Text
section. The risk is accessibility focus handling for the overlay; keep the
`importantForAccessibility` logic.

## 3. Substantial improvements

### S1: Session-aware screen timeout and cover timer

**Problem and evidence:**

- `FLAG_KEEP_SCREEN_ON` holds the inner screen on for the **whole time a book is open**
  (`MainActivity.kt:238`). If the reader falls asleep, the screen never turns off. This is
  BC-22, still open.
- The cover timer is a fixed countdown from when the cover starts
  (`cover/ExteriorCoverController.kt:100`; 30 minutes by default). The cover switches off
  mid-session, and the only notice is inside the controls.

**Benefit:** Battery life, OLED burn-in protection, and a signature feature that does not
quietly vanish halfway through a chapter.

**Recommended change:**

- Keep the screen on only for a set idle time after the last page turn or touch (5 minutes
  by default; options 2, 5, 10 minutes or Always). After that, let the phone's normal
  timeout apply.
- Change the cover timer to "off after N minutes **without a page turn**", restarting the
  countdown on every turn.
- When the cover stops for any reason, show a one-line, non-blocking notice with a "Turn
  back on" action.

**Acceptance criteria:**

1. Leave a book open and untouched: the inner screen sleeps after the chosen idle time.
2. Turn a page every few minutes for 45 minutes with a 30-minute timer: the cover stays
   on, which the owner confirms by looking.
3. Record a 60-minute battery drain figure.

**Effort and dependencies:** Small to medium. Do it alongside or right after C2, since both
touch the cover's start/stop flow.

### S2: Library home that gets the reader back into the book

**Problem and evidence:**

- **About 40% of the screen sits above the first cover** (0.3.0 screenshot;
  `library/LibraryScreen.kt:57–92` still has the same structure). That covers the title and
  tagline, Settings and Import, a full-width search field, collection chips, the book
  count, sort chips, a status line and the Continue pill.
- Reading tips show up in the library, such as "Swipe to turn pages…"
  (`MainActivity.kt:166`).
- **Continue is a text-only pill** (`LibraryScreen.kt:88`).
- Covers with unusual shapes are letterboxed (`ContentScale.Fit`, line 103; the Dresden
  Files omnibus shows this).
- Progress appears only as a percentage.
- Tapping a cover gives no feedback at the cover itself while the book loads.

**Benefit:** Returning to the current book is the most frequent action in any reader, and
this makes it the visual hero.

**Recommended change:**

- A compact top bar: the title, a search icon that expands, a sort menu, Settings in an
  overflow menu, and Import as an icon or floating button.
- A **Continue card**: cover thumbnail, title, chapter name, progress bar and a Resume
  button.
- A thin progress bar under each cover.
- Fill around non-standard covers with the cover's edge colour (`MatchingCoverColor`
  already exists).
- Status messages only for errors or as short-lived banners.
- A loading indicator on the cover that was tapped.

**Acceptance criteria:**

1. The first row of covers starts within the top 25% of the screen when unfolded.
2. The Continue card opens the last book at its saved position.
3. Accessibility tests pass at 200% font size.

**Effort and dependencies:** Medium. It touches only the library files, so it can run in
parallel in the UI lane.

### S3: Two-page spread when unfolded

**Problem and evidence:**

- `reader/ReadingComfortPreferences.kt:38` hard-codes `ColumnCount.ONE`.
- With the 800px line cap, the reader screenshot uses only about 65% of the unfolded width;
  the rest is margin.
- BC-19 is open.

**Benefit:** It makes the unfolded screen feel like an open book, the best use of the
Fold's distinctive hardware.

**Recommended change:**

- Add a "Pages: Auto / One / Two" setting in the Aa sheet.
- Auto shows two columns when the phone is unfolded and the width can fit two lines of at
  least about 30em each; it stays at one column when folded.
- Use WindowManager's fold information (`FoldingFeature`) to keep the gap between columns
  on the crease.

**Acceptance criteria:**

1. Unfolded: two columns, with no text crossing the crease.
2. Folded: one column.
3. Switching layouts keeps the visible paragraph on screen.

**Effort and dependencies:** Medium. It **depends on C2**; otherwise every fold reloads the
book anyway.

## 4. Recommended execution order

1. **C1: Unlock text size**, the single best next task. It is a small change that fixes a
   broken first experience for every new user, and nothing has to come before it.
2. **H3: Slim reader bar and Aa sheet**, which builds on the Text section C1 just fixed.
3. **C2: Fold and unfold without reloading** (quick fix first).
4. **S1: Session-aware screen and cover timers**, which follows C2's changes to the cover's
   start/stop flow.
5. **S2: Library home redesign.** It can run in parallel with steps 2–4 in the UI lane,
   since it touches only library files.
6. **S3: Two-page spread**, after C2.
