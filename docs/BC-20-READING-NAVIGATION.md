# BC-20: chapter progress, Contents and named bookmarks

Baseline: `05be6ea` (October 26, 2026). The tracked baseline source archive and a separate patch preserving concurrent BC-18 work are in ignored `output/BC20-baseline/`. Claude Code subsequently committed BC-18 as `3741061`; the completed BC-20 build includes that correction. BC-18 changes are not attributed to BC-20's commits.

## Acceptance criteria

- Revealed controls show progress within the current chapter; the quiet page retains only its existing whole-book percentage.
- A valid multi-page chapter advances as pages turn. Final chapters, missing Contents and image/fixed resources do not produce invalid percentages.
- Nested Contents entries pointing to different fragments of one XHTML resource are resolved from the rendered layout. The current entry is highlighted and automatically visible when Contents opens.
- Explicit navigation to a subsection sharing a physical page with another subsection retains the chosen entry among that page's tied boundaries.
- A bookmark can be named, renamed, reopened and removed. Repeated saves at the same precise location update the name instead of adding a duplicate. Old bookmarks and backups remain readable without migration.
- Reading-time estimates remain unavailable until at least three valid forward intervals totaling 45 seconds are measured. Controls, backgrounding, explicit jumps, rapid flipping, long idle gaps and typography changes do not become reading-speed samples.
- Existing reflow, font settings, native swipes, optional RTL edge taps and outer-cover code remain in place. Only the owner can confirm the physical outside display.

## Implementation

`ChapterProgress.kt` implements pure chapter arithmetic over publication coordinates. `ReadingChapters.kt` adapts public Readium positions and Contents links. It interpolates resource progression within the publication's position intervals; byte-based Readium positions are never labeled as rendered pages.

Same-resource fragment headings are measured with a bounded read-only DOM geometry query through Readium's public JavaScript API, without extracting page text or modifying the document, scroll or selection. The query uses the fractional CSS viewport width corresponding to the native view and normalizes by the rounded page count, matching Readium's pagination. Layout generations prevent old asynchronous queries from restoring stale geometry after reflow. Explicit navigation hints only select among tied active-page boundaries. Missing or unresolvable anchors produce a clearly labeled section fallback; unknown chapter endpoints suppress exact chapter percentages rather than inventing them. At most 512 fragment entries are measured per current-resource update, and Contents nesting is capped at 32 levels.

`ReadingPace.kt` learns publication-distance per elapsed interval using monotonic time, with no assumed words-per-minute rate. It retains the latest 20 eligible intervals for the open reading session. It rejects backwards movement, individual jumps over 10% of the book, intervals shorter than three seconds and idle gaps longer than five minutes. Lifecycle pauses, controls, reflow, viewport changes and Readium's explicit-jump callback break the candidate interval while retaining already learned samples. Estimates are approximate and are not persisted between sessions.

`SavedBookmarks.kt` keeps the existing `List<String>` locator JSON schema. Names use the `locations.bookcoverBookmarkName` extension; the locator title retains chapter context. Unknown fields and malformed legacy entries are preserved. Duplicate matching uses precise local progression or compatible selector/fragment/text evidence, not a resource or coarse global position alone. Explicit duplicate saves may rename the existing target without moving it. Backup merges preserve local names and may fill missing names. Existing legacy duplicates are not retroactively deleted.

`ReaderContentsPanel.kt` contains the revealed chapter summary, current-row marker and bookmark name dialog. Contents uses a bounded lazy list initialized at the current entry, keeping long tables responsive. `EpubReaderScreen.kt` retains the reading host and original typography/gesture controls. `MainActivity.kt` wires the real save/rename actions. The separate QA host now observes its isolated library records and uses real bookmark callbacks, so UI validation exercises persistence rather than no-op controls.

Phone testing also found Readium's RTL fragment jump could land one physical page before a subsection. Contents navigation now uses the measured page progression through Readium's public locator API. For a fragment in an unvisited resource, it first opens the resource and refines that explicit jump once after measuring the destination. A swipe or manual page turn cancels pending refinement. No custom scrolling, swipe recognizer or document mutation was introduced.

## Validation record

Initial compilation and JVM checks passed. Phone testing found a fractional-width chapter-boundary error that plain arithmetic tests could not expose: CSS scroll width rounded to an integer while Readium used native pagination fractions. The adapter now uses the same rounded page count, with a small floating-point comparison tolerance. Tests also exercise multiple subsection starts sharing a physical page.

Final results on the unfolded Samsung SM-F976U, Android 17:

| Check | Result |
| --- | --- |
| Debug APK, QA APK/test compilation | Passed |
| JVM tests, including chapter arithmetic, measured pace, bookmark compatibility and backup merging | 85 passed, zero failures |
| New real-touch chapter/Contents/bookmark UI tests | 5 passed |
| Existing native typography, font persistence, LTR/RTL prose/image taps, selection and links | 12 passed |
| Native library import, persistence, corruption safety and named-bookmark round trip | 11 passed |
| Unfolded fixed-margin/font-size regression | 1 passed, not skipped |
| Combined native regression run | 29 passed together, zero failures or skips |
| Debug / QA lint | Zero errors; 23 / 24 warnings |
| APK signature and 16 KiB alignment | Verified |

The UI tests locate controls through Android accessibility and act through real touchscreen events. They clear stale accessibility caches, accept Android's selected/checked state variants, and wait for moving controls to settle before taps. Initial accessibility-action and fling-related test failures were retained as diagnostic evidence; the final real-touch run passed. No private book or page screenshot was captured.

The validated APK was installed in place as `output/BookCover-BC20.apk`, SHA-256 `f057aec9aa38bc7f2fdcd98c112287fdce4c4f56de495de28b31a5b3bc9fd491`. Its version label remains 0.4.0-prototype / code 6. Existing books, bookmarks and preferences were retained. The review folder and ZIP are `output/BookCover-BC20-review/` and `output/BookCover-BC20-review.zip`; they include current source, build/test evidence, baseline material and setup instructions.

Device tests ran only through `scripts/Test-Device.ps1` against `com.bookcover.diagnostic.qa`; the owner's installed reader was never the instrumentation target. Natural reading-time calibration, manual usability and the physical outside cover still await owner observation for this build. No claim is made that every published EPUB, missing anchor or fixed-layout subsection can provide exact chapter boundaries. Remaining UI organization work belongs to BC-23; fold/unfold continuity remains BC-07/BC-15, and two-page spreads remain BC-19.
