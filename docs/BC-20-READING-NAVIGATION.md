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

`ReadingPace.kt` learns publication-coordinate distance ($\Delta p \in (0, 1]$) per elapsed interval ($\Delta t$) using monotonic time (`SystemClock.elapsedRealtime()`), with no assumed or displayed words-per-minute rate. It retains the latest 20 eligible intervals for the open reading session (`MIN_SAMPLES = 3`, `MIN_TOTAL_ELAPSED_MILLIS = 45_000L`). It filters sub-page floating-point drift ($\le 10^{-6}$) without resetting the active page timer, requires forward progress in $[0.00001, 0.10]$ (one screen of a ~100,000-screen book) over $[3\text{ s}, 300\text{ s}]$, enforces an upper single-interval pace ceiling of $1\%/\text{s}$ (`MAX_PACE_PER_MILLIS = 0.01 / 1_000.0`), and rejects coalesced multi-page skim bursts ($> 2.5\times$ median sample distance at $> 3\times$ weighted median rate once $\ge 3$ samples exist). When a reader flips backward within $10\%$ of the book without an explicit jump, `ReadingPace` records a forward-retrace high-water mark (`retraceTarget`) so flipping forward across already-read pages does not count as fast new reading; the mark survives control/lifecycle pauses and is dropped when the next position after a pause is past it or more than 10% behind it (a real jump). Rate estimation uses recency-decayed weights ($\gamma = 0.85$ per newer sample) with MAD-based Winsorization around the weighted median per-interval rate, preventing a single long near-threshold pause ($290\text{ s}$) from dominating the estimate while adapting within 5–6 turns to genuine pace shifts. Remaining chapter estimates return `0` (`"Less than a minute left in chapter"`) when remaining time is in $[0, 0.5)$ minutes, `ceil(minutes - 1e-9)` for $[0.5, 600.0]$ minutes, and `null` above `600` minutes (`MAX_REMAINING_MINUTES = 600`). Lifecycle pauses, controls, reflow, viewport changes and Readium's explicit-jump callback break the candidate interval while retaining already learned samples. Estimates are approximate and are not persisted between sessions.

`SavedBookmarks.kt` keeps the existing `List<String>` locator JSON schema. Names use the `locations.bookcoverBookmarkName` extension; the locator title retains chapter context. Unknown fields and malformed legacy entries are preserved. Duplicate matching uses precise local progression or compatible selector/fragment/text evidence, not a resource or coarse global position alone. Explicit duplicate saves may rename the existing target without moving it. Backup merges preserve local names and may fill missing names. Existing legacy duplicates are not retroactively deleted.

`ReaderContentsPanel.kt` contains the revealed chapter summary, current-row marker and bookmark name dialog. Contents uses a bounded lazy list initialized at the current entry, keeping long tables responsive. `EpubReaderScreen.kt` retains the reading host and original typography/gesture controls. `MainActivity.kt` wires the real save/rename actions. The separate QA host now observes its isolated library records and uses real bookmark callbacks, so UI validation exercises persistence rather than no-op controls.

Phone testing also found Readium's RTL fragment jump could land one physical page before a subsection. Contents navigation now uses the measured page progression through Readium's public locator API. For a fragment in an unvisited resource, it first opens the resource and refines that explicit jump once after measuring the destination. A swipe or manual page turn cancels pending refinement. No custom scrolling, swipe recognizer or document mutation was introduced.

## Validation record

Initial compilation and JVM checks passed. Phone testing found a fractional-width chapter-boundary error that plain arithmetic tests could not expose: CSS scroll width rounded to an integer while Readium used native pagination fractions. The adapter now uses the same rounded page count, with a small floating-point comparison tolerance. Tests also exercise multiple subsection starts sharing a physical page.

Final results on the unfolded Samsung SM-F976U, Android 17:

| Check | Result |
| --- | --- |
| Debug APK, QA APK/test compilation | Passed |
| JVM tests, including chapter arithmetic, measured pace, bookmark compatibility and backup merging | 85 passed, zero failures (expanded in follow-up calibration pass; see below) |
| New real-touch chapter/Contents/bookmark UI tests | 5 passed |
| Existing native typography, font persistence, LTR/RTL prose/image taps, selection and links | 12 passed |
| Native library import, persistence, corruption safety and named-bookmark round trip | 11 passed |
| Unfolded fixed-margin/font-size regression | 1 passed, not skipped |
| Combined native regression run | 29 passed together, zero failures or skips |
| Debug / QA lint | Zero errors; 23 / 24 warnings |
| APK signature and 16 KiB alignment | Verified |

The UI tests locate controls through Android accessibility and act through real touchscreen events. They clear stale accessibility caches, accept Android's selected/checked state variants, and wait for moving controls to settle before taps. Initial accessibility-action and fling-related test failures were retained as diagnostic evidence; the final real-touch run passed. No private book or page screenshot was captured.

The validated APK was installed in place as `output/BookCover-BC20.apk`, SHA-256 `f057aec9aa38bc7f2fdcd98c112287fdce4c4f56de495de28b31a5b3bc9fd491`. Its version label remains 0.4.0-prototype / code 6. Existing books, bookmarks and preferences were retained. The review folder and ZIP are `output/BookCover-BC20-review/` and `output/BookCover-BC20-review.zip`; they include current source, build/test evidence, baseline material and setup instructions.

Owner confirmation, October 26, 2026: Frank tested and confirmed Contents, bookmarks and chapter progress on the installed build. He explicitly left reading speed TBD. These three core features are owner-confirmed; natural reading-speed/time-left calibration remains unconfirmed, despite the automated pace-policy tests passing.

Follow-up pace calibration pass (October 27, 2026, branch `argon/bc20-pace-calibration`): Expanded `ReadingPaceTest` from 8 to 17 deterministic JVM unit tests (`Build.ps1 -TaskId bc20-pace -Owner Argon`: 257/257 unit tests passed, `assembleDebug`, `lintDebug`, `lintQa` passed; task `bc20-pace-8a60708d62ea442a`, source snapshot `8304137d8a27ce5a99fa02a57753bacd9b2bb8820067962d950d8e1ab0ec6118`, evidence manifest `8304137d8a27ce5a99fa02a57753bacd9b2bb8820067962d950d8e1ab0ec6118-aef3403ff4c8423a9b599b49d6b9a4f2.json`) comparing the baseline sum-ratio estimator (`af0695b`) against the calibrated Winsorized recency-weighted estimator across steady reading speeds ($1\%/\text{min}$, $2\%/\text{min}$, $4\%/\text{min}$, $\pm 20\%$ jitter), floating-point micro-drift ($10^{-7}$), single distracted turns ($290\text{ s}$), coalesced multi-page flip bursts, backward turns with forward retrace, $2\times$ pace transitions, typography repagination (`pause()` + changed per-page distance step), sub-minute/600-minute numerical boundaries, and two held-out multi-phase synthetic sessions. Synthetic tests establish algorithm behavior only; owner natural-reading confirmation on physical hardware remains explicitly pending.

Review follow-up (October 27, 2026, `claude/bc20-pace-calibration-review-5b20c5`): the `0.0002` minimum step would have silenced time-left in books over ~5,000 screens, so it is now `0.00001`; the re-read mark is no longer erased by opening controls mid-retrace. Two new `ReadingPaceTest` cases (20,000-screen book; re-read mark across a controls pause vs. a far Contents jump). `Build.ps1 -TaskId bc20-review -Owner Claude`: **259/259 unit tests** (`ReadingPaceTest` 19/19), `assembleDebug`, `lintDebug`, `lintQa` zero errors; task `bc20-review-2b02507aac1b942e`, source snapshot `ce28d4c4ae495d8a457417d17e1a0be5ac9ded0c6b9022a868fd1cb486bb5a86`. Owner natural-reading check still pending.

Device tests ran only through `scripts/Test-Device.ps1` against `com.bookcover.diagnostic.qa`; the owner's installed reader was never the instrumentation target. The physical outside-cover observation for this build remains unrecorded. No claim is made that every published EPUB, missing anchor or fixed-layout subsection can provide exact chapter boundaries. Remaining UI organization work belongs to BC-23; fold/unfold continuity remains BC-07/BC-15, and two-page spreads remain BC-19.
