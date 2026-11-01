# Reading Relics — catalog and cover styles

**Status:** all three sets are implemented and installed in place on the owner's phone. Bookplates are removed. New-set physical appearance and animation remain for the owner to confirm.

**Current scope:** the owner approved the narrow-screen edge appearance and requested removal of Bookplates plus rollout of Wayfarer's Atlas and Hearthkeeper's Collection on 2026-11-01. The catalog contains three sets of six relics. Bookplates, ownership inscriptions and their reserved cover space are removed. Original Moonlit IDs, reading credit, existing awards, adaptive colors, live outside previews and optional Mythic motion remain supported. The rollout's build/device/install evidence belongs in the active backlog and the validation record below; older concept and Moonlit-only evidence is historical.

## Product intent and earning

Reward reading with a permanent collection of restrained literary-fantasy symbols and matching Cover Edges. Each set spans Common, Uncommon, Rare, Epic, Legendary and Mythic. Rarity names stay visible; recognition color supplements the label. The core reader remains available independently of rewards.

Credit the existing foreground EPUB/PDF reading clock, its idle cutoff and its stored qualifying-day flags. A qualifying day currently requires five cumulative reading minutes. Dates remain local calendar dates; timezone changes do not relocate prior reading. Rewards are granted once, retroactively when existing history qualifies, and never removed after a missed day. Earlier reading uses an honest retroactive label rather than an invented date. No random drops, duplicate items, currency, paid boosts, GPS or time-of-day tracking are involved.

## Moonlit Archive

Quiet scholarship, quills, keys, crescents and constellations. Earned from cumulative credited reading time.

| Rarity | Relic | Milestone |
| --- | --- | --- |
| Common | Archivist's Quill | 5 total reading minutes |
| Uncommon | Midnight Lamp | 1 total reading hour |
| Rare | Silver Key | 5 total reading hours |
| Epic | Orrery of Margins | 15 total reading hours |
| Legendary | Moonbound Grimoire | 40 total reading hours |
| Mythic | The Endless Archive | 100 total reading hours |

## Wayfarer's Atlas

Maps, route lines, lenses and compass markers. Earned from cumulative qualifying reading days; the days need not be consecutive.

| Rarity | Relic | Milestone |
| --- | --- | --- |
| Common | Trail Ribbon | 1 qualifying reading day |
| Uncommon | Pocket Compass | 3 qualifying reading days |
| Rare | Cartographer's Lens | 7 qualifying reading days |
| Epic | Map of Hidden Roads | 21 qualifying reading days |
| Legendary | Atlas of Distant Realms | 60 qualifying reading days |
| Mythic | Compass of Every Horizon | 180 qualifying reading days |

## Hearthkeeper's Collection

Sparks, candles, seals, feathers and a returning flame. Either the best-ever streak or cumulative qualifying days earns the same item, with both progress routes shown equally. Missing a day never removes a collected relic.

| Rarity | Relic | Milestone — either route |
| --- | --- | --- |
| Common | First Spark | Best streak 3 days OR 10 total qualifying days |
| Uncommon | Keeper's Candle | Best streak 7 days OR 25 total qualifying days |
| Rare | Hearth Seal | Best streak 14 days OR 50 total qualifying days |
| Epic | Phoenix Feather | Best streak 30 days OR 100 total qualifying days |
| Legendary | Lantern of the Long Watch | Best streak 60 days OR 180 total qualifying days |
| Mythic | The Everlasting Hearth | Best streak 100 days OR 365 total qualifying days |

## Relics and Cover Edges

The original minimal cabinet symbols remain the baseline. Add only a subtle second tone, small highlight or tiny accent; retain recognizable silhouettes and quiet space. There are two independent choices: a relic seal and Cover Edges. Both have None, and families can be mixed. Browsing a set does not equip it; tapping an item applies its draft preset. Bookplates and ownership-text controls are no longer part of the product.

| Rarity | Cover treatment |
| --- | --- |
| Common | Relic seal; no Cover Edges yet. |
| Uncommon | Refined relic seal; no Cover Edges yet. |
| Rare | First usable family corner brackets. |
| Epic | Fine connecting lines and a small family crest. |
| Legendary | Restrained double-corner accents and tiny coordinated marks. |
| Mythic | Complete family treatment with optional slow 10-second edge motion and a static option. |

Moonlit retains the approved clean corners, crescent crest and stars. Atlas uses contour/route corners and a compass crest. Hearthkeeper uses warm corner details and ember/flame motifs. Their original palettes are respectively muted lunar, blue-green and copper/amber; the normal Auto choice harmonizes with the actual book cover instead. Avoid dense filigree, painterly objects, scenery, dramatic glow or rarity-dependent brightness.

Only Mythic Cover Edges animate. The source artwork and relic symbol stay still. Adaptive palette sampling and static cover preparation are cached; animation does not resample/recolor the source on every frame. Motion respects system animation settings and pauses when clipped, hidden, detached, backgrounded or released. A thin contrasting keyline keeps the trace legible over mixed artwork without adding a glow.

## Cover modes and adaptive colors

- **Added margin (default):** fully fit the original cover inside a centered book-shaped composition with visible side rails. The frame follows the book instead of the tall screen's extremes. No image cropping and no reserved Bookplate strip.
- **Inset overlay (optional):** place edges inside the fitted original art. An edge can overlap cover text or artwork; preview it explicitly and return to margin mode when desired. The source file is never flattened or edited.
- **Standalone:** use a family-themed template with the book's title and author. No source cover is required. Relic and edge choices remain independent.

Auto colors follow the cover. Light, Dark, Relic family palette and a manual RGB accent remain available. Recognition colors in the cabinet do not force the overlay to use rarity colors. Keep controlled foreground/background contrast in standalone text and opposing keylines on decorations. The preview follows the actual outside display aspect ratio, with a narrow portrait fallback; the outside renderer respects cutout and system-bar insets.

## Cabinet and live outside preview

1. Open **Library → Relic cabinet**, or **Settings → Outside cover**.
2. Choose a set, inspect a relic and its exact requirement, then choose a global or per-book appearance.
3. Change edge/seal families, tier, cover mode, colors or Mythic motion. Changes request a live outside preview while the editor is open. Keep the phone unfolded and accept Android's display prompt if offered. Status and **Start outside preview** support explicit retry when unavailable.
4. **Save appearance** retains the choice; **Done** discards the draft. A per-book override can be reset to the global default. Locked items can be previewed but require earned or temporary testing access to save.

Preview owns only its temporary display request. Closing the editor restores saved reader content and its original timer/Stop choice, or closes a preview opened from the library. Draft changes do not renew a reader's countdown. Restore/resize and delayed callbacks remain session-bound; Stop and screen-off cancel presentation work.

## Temporary owner testing

Debug/QA testing access makes all 18 items usable for 24 hours without changing genuine reading credit, awards or permanent appearance settings. Testing Saves affect a separate local profile, excluded from library backups. **End testing**, or expiry, restores the genuine profile. Reopening the cabinet or updating from the earlier active Moonlit testing window does not extend its deadline. The explicit owner setup intent can open the cabinet; an existing active window is retained.

## Persistence and compatibility

Reward document version 2 records total reading time, cumulative qualifying days, best-ever streak, genuine awards, dated reading floors with original completion flags, and independent edge/seal families. Version 1 Moonlit documents and earlier metadata backups remain readable. Existing Moonlit IDs keep their meanings. Old Bookplate fields are accepted for migration but no longer equip, render or reserve space.

Restore merges dated records by maximum time and retained completion, unions permanent awards, and preserves explicit local global/book choices and reset-to-inheritance decisions. Counts and best streak retain earned high-water values while dated unions discover newly qualifying routes. Repeating a restore is idempotent. Individual file replacements are atomic; the whole multi-file restore remains retryable rather than one transaction. Corrupt reward copies fail closed while healthy books stay accessible. The app stays offline, and original publication/cover files remain unchanged.

## Owner observations and validation

The owner reported that the current edges look good on 2026-11-01 and requested this expansion. That is appearance approval; it does not independently confirm every new set, animation, whole-app path or battery effect. The latest rollout verification and installed build are recorded in [the active backlog](review/ACTIVE-BACKLOG.md).

Implementation `af126a4` passed the guarded build, debug/QA lint, **400/400 unit checks** and **26/26 isolated Android checks**, with zero skipped. Tests cover all 18 rendered glyphs, all three families/modes, actual Mythic motion with static-cache reuse and teardown, source-pixel preservation, legacy Bookplate neutrality, mixed-family saves, both Hearth earning routes, version 1 migration, cross-family backup/restore and 200% controls. The glyph sheet and Atlas/Hearth standalone renders were visually inspected using generated fixtures only.

Source snapshot: `2599c08e3aa95e46e5021cfd187f9421b0d1a71ecba604edbb7fe4499eb351f8`. Combined evidence under task `moonlit-f44b4cc347ee7e18`:
`2599c08e3aa95e46e5021cfd187f9421b0d1a71ecba604edbb7fe4499eb351f8-6a0cded0447a49488693e68155e0a2b2.json`.

Installed in place on 2026-11-01 at 15:15 (America/New_York). Installed code SHA-256 matches `0d50cafc9ac205e5a556c06ebe602b934ede4f5ab2fe2837634108e1496ab6e7`; signing identity and the original installation date were preserved. Existing testing start/expiry remained unchanged, through **2026-11-02 14:50:59 America/New_York**, now covering all 18 relics. The cabinet reopened for testing. No main-app instrumentation, data clear or personal-book screenshot/file inspection was used. The new exterior styles, animation and whole-app appearance still need owner observation.

## Historical design verification

The following records describe the earlier concept previews. Native implementation evidence is recorded separately below.

This draft follows a read-only inspection of the existing tracker: stored daily time, qualifying-day flags and best/current streak support the proposed milestone inputs for EPUB and PDF. These are design and data-availability findings, not newly run app tests. No app code, installation or device tests were performed for this design work.


### Full Moonlit Archive concept preview verification (2026-11-01)

The earlier full-set interactive preview covers all six exact names and milestones. These checks establish that preview only; they do not establish completion of the later adaptive appearance, inset overlay or standalone work. Browser checks verified each staged appearance: no decorations at Common, Bookplate at Uncommon, corners at Rare, lines and crest at Epic, added star/corner accents at Legendary, and margin motion plus prestige Bookplate at Mythic. The animated traveler changed position during playback (260.219 px to 72.7656 px); Pause held its position at 70.0156 px across separate observations and Play resumed the running state. The Bookplate toggle worked, and the 320 px viewport had equal client/scroll widths, with no horizontal overflow. Reduced-motion and document-hidden fallbacks are present in the preview source; those host preference/visibility transitions were not separately exercised. No app, device, performance or battery validation is implied.

At the initial read-only design review, the outside presentation used a static ImageView. The review identified the need for a separate decoration layer, measured margin/inset/template bounds, local palette and contrast handling, and animator teardown on Stop, screen-off, timeout, session end and controller close. Preserve the existing cover deadline and full-image fit. That historical inspection did not implement or verify an adaptive algorithm or a new presentation mode.


### Adaptive preview verification (2026-11-01)

The separate adaptive-appearance demonstration uses real canvas pixel sampling from generated synthetic covers. Browser checks covered Forest, Crimson, Ivory, Night, Ocean and busy monochrome, plus custom black, white, mid-gray, bright yellow, blue and magenta. Auto selected cover-related pale/deep tones; busy monochrome retained a neutral accent. Controlled opaque Bookplate foreground/background contrast was at least 12.1:1 in these samples. The primary stroke alone had weak contrast over some busy pixels, confirming the need for the opposing keyline or added margin rather than a universal visibility claim.

A full-canvas checksum stayed `7ba0ace2` while light/dark/set/auto treatments, tier, margin placement and decoration visibility changed on the same Forest sample. The source artwork was not repainted or filtered by those controls. Standalone rendered its own title/author template and selected relic label, with separate Common/Mythic canvas checksums. The Bookplate text/backing transition issue found during review was corrected: the pair switches together while only the border interpolates. The 320px viewport had equal client/scroll widths with no horizontal overflow. The demonstration remains a heuristic and design preview, not the shipping app algorithm or phone validation.


## Initial Moonlit implementation validation (2026-11-01)

The implementation contains the permanent local collection, cabinet/editor, local palette and contrast handling, full-fit margin/inset/standalone rendering, and a cached static cover with an optional 10-second Mythic trace. Motion honors system animation settings and pauses on hidden/clipped/detached views, host backgrounding and presentation teardown without renewing the existing cover deadline. Original publication and cover files are unchanged.

Implementation `a2d2384` and native-test follow-up `242409d` passed the guarded build, **369/369 JVM checks**, debug/QA lint and **16/16 isolated Android checks** with zero skipped. Tests used task-owned read-only `emulator-5556`, generated fixtures and the bundled sample books. They exercised backup roundtrip/idempotency/corruption safety, original pixel preservation, margin/inset/standalone drawing, real moving Mythic pixels with cached-cover reuse, visibility/teardown pauses, locked choices, global/per-book Save/reset and 200% text labels and activation. A short-window native preview was also inspected during integration. Relic tiles are repeatable labeled buttons, so an already selected item can still apply its preview preset.

Native motion evidence recorded system animations enabled, phase advancing from `0.0037` to `0.0404`, changed rendered pixels, unchanged preparation count, a held hidden phase, clipping pause/resume and terminal release. These are native-view checks, not physical exterior-display evidence.

Task: `moonlit-f44b4cc347ee7e18`. Source snapshot: `bb597ebb8162185d74e816e93f508e4eb68b73bb20e1a1432f434bbbefeab821`. Combined source-bound evidence under `%LOCALAPPDATA%\BookCoverBuild\tasks\moonlit-f44b4cc347ee7e18\evidence\`:
`bb597ebb8162185d74e816e93f508e4eb68b73bb20e1a1432f434bbbefeab821-a82e9cf14f8348d2900c4c9c039bf4f9.json`.

Initial APK: `BookCover-moonlit-f44b4cc347ee7e18.apk`; SHA-256 `47ab645728f82c229b9b0c68d33e2f0cdb1f5602bab9385490849c49259b47dd`. Installed in place on the owner's phone on 2026-11-01 at their request; installed APK readback matched and the original installation time was unchanged. The generated APK, receipts and screenshots remain outside Git. Exterior appearance, performance and battery use still require owner observation; previous PDF outside-display confirmation remains recorded separately. The testing/live-preview follow-up has its separate validation record in the active backlog.

### Live outside preview and temporary testing validation

Follow-up `5aebb24` passed the guarded build, **385/385 JVM checks**, debug/QA lint and **21/21 isolated Android checks**, with no skipped tests. Coverage includes test-access expiry and profile/backup isolation, preservation of the original reader deadline and Stop choice, narrow-display original pixels and visible rails, real Mythic motion and cached content reuse, live cabinet callbacks, accessible controls at 200% text, and the real activity's explicit-intent activation and preview teardown. Generated narrow margin and standalone renderings were visually inspected. A task-owned local Android bridge and read-only emulator isolated the completed run from interruptions to the shared connection.

Source snapshot: `a0657b8bce7676363cb5aa6ff4e93d6b7fcdc0cbb0d8d7bcd642a04db3b1d73c`. Combined evidence in the same task's evidence directory:
`a0657b8bce7676363cb5aa6ff4e93d6b7fcdc0cbb0d8d7bcd642a04db3b1d73c-621a8fe3f138425bb9bd50dafc5e19f0.json`.

Installed in place on the owner's phone on 2026-11-01 at 14:50 (UTC−5), then opened the cabinet with testing access confirmed through **2026-11-02 14:50:59 (UTC−5)**. Installed APK readback matches SHA-256 `432b6b76408305efdea18cd9e9928830f6aa5b157a754ebd7740e7bcf7b148cb`; signing identity matches and the original install time remains unchanged. System animator scale was 1. No main-app instrumentation, data clear or personal-book screenshot/file inspection was used. The owner must still confirm the physical outside-screen appearance and animation; automated preview requests do not establish that observation.
