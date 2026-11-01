# Reading Relics — Relic cabinet design draft

**Status:** Moonlit Archive implementation authorized by the owner on 2026-11-01, including adaptive colors, optional overlays, standalone display and animated Mythic Cover Edges. Use the six Moonlit milestones and approved restrained style below. Wayfarer's Atlas and Hearthkeeper remain future design. The native Moonlit implementation is complete and passed build, unit, lint and isolated Android validation. Owner-phone installation and inspection of the new outside appearance remain pending. Wayfarer/Hearthkeeper concepts and historical browser previews are separate from this app implementation.

## Product intent

Reward time spent reading with a permanent collection of original literary-fantasy relics. A reader can collect individual pieces, unlock matching cover decorations and complete a set for a more elaborate finish. All readers retain the full reading experience regardless of their collection.

Start with **three sets of six relics**, for 18 collectibles. Each set spans Common, Uncommon, Rare, Epic, Legendary and Mythic. Rarity belongs to the item, not a universal reader level; milestones in different sets can be reached in any order. This is BookCover's own simplified RPG-inspired system, not an exact reproduction of either WoW or Diablo.

## Shared rarity language

| Tier | Recognition color | Proposed art treatment |
| --- | --- | --- |
| Common | Pearl / neutral | A clear minimal symbol with a simple silhouette. |
| Uncommon | Green | The same restrained symbol style with one subtle second tone. |
| Rare | Blue | A recognizable emblem with a small highlight or tiny accent. |
| Epic | Violet | One small secondary motif and a short piece of lore; keep the silhouette clear. |
| Legendary | Amber / orange | A distinctive named emblem with a restrained border or corner detail. |
| Mythic | Pale gold | A polished signature emblem and coordinated prestige finish, within the same minimal style. |

Keep names and written rarity labels visible; color is supplementary. The recognition colors in this table identify rarity in the cabinet, not a mandatory frame or Bookplate color over a book. Separate the set's shape, tier detail and motion from its palette: a Legendary item need not put an orange frame on every cover, and Moonlit geometry need not remain silver. Outside decoration normally uses a harmonious cover-derived accent; the original set palette remains an explicit override. Higher rarity uses small, controlled distinctions within the original cabinet style, without increasing illustration density or screen brightness. Relic symbols and Bookplates stay still; optional motion is reserved for Mythic Cover Edges as specified below.

## Art direction — approved style, assets in progress

The **original Relic cabinet is the visual baseline**. The owner wanted only a touch more character than the first minimal symbols and rejected both subsequent detailed raster studies as overcorrections. The latest subtle direction is now approved: "Like that lets build out a full set lets make sure the Cover Edges evolve Mythic Cover edges should be animated" (2026-11-01). The owner subsequently authorized implementing this Moonlit direction in the app, including the adaptive appearance requirements below.

Retain the clear minimal cabinet symbols and familiar simple settings, adding **one subtle second tone, a small highlight or a tiny decorative accent**. Preserve generous quiet space and a recognizable silhouette at cabinet and cover-seal sizes. Do not replace the baseline with painterly objects, storybook illustrations or more elaborate compositions.

Bookplates remain quiet ownership labels with a modest border and a small set symbol. Cover Edges evolve from four usable corner brackets into slightly richer, coordinated tier variants. The default layout preserves the full original cover inside an added decorative margin; the optional inset overlay and standalone layouts below reuse that geometry. Decoration color adapts independently to the chosen cover or standalone background. Moonlit Archive's tier evolution is specified below.

Photoreal textures, material realism, dense filigree, gemstone encrusting, scenery and dramatic glow remain outside this direction. Rarity differences stay small and deliberate within one consistent minimal family.

Relic symbols, Bookplates and original cover artwork stay static. Mythic Cover Edges are the sole requested motion exception: a slow subtle trace confined to the decoration bounds, with an off control, reduced-motion behavior, hidden-state pause and static fallback. The path and tier geometry are unchanged by adaptive color or presentation mode. The native Moonlit implementation follows this direction. Set names, rarity tiers and unlock thresholds remain as agreed; the other two sets remain future design.

## Set 1 — Moonlit Archive

**Theme:** quiet scholarship, ink, silver, crescents and the mystery of a vast library. **Path:** cumulative credited reading time.

| Rarity | Relic | Unlock milestone |
| --- | --- | --- |
| Common | Archivist's Quill | 5 total reading minutes |
| Uncommon | Midnight Lamp | 1 total reading hour |
| Rare | Silver Key | 5 total reading hours |
| Epic | Orrery of Margins | 15 total reading hours |
| Legendary | Moonbound Grimoire | 40 total reading hours |
| Mythic | The Endless Archive | 100 total reading hours |

- **Bookplate, 2 of 6 / Uncommon / 1 hour:** the standard Ex Libris label with a small quill and crescent. Common shows the relic only; neither Common nor Uncommon unlocks Cover Edges.
- **Cover Edges, 3 of 6 / Rare / 5 hours:** the first usable edge style is four simple silver corner brackets. Later relics evolve that unlocked style at the same existing milestones.
- **Prestige pair, 6 of 6 / Mythic / 100 hours:** a subtly refined Bookplate and the complete constellation edge treatment. Keep the standard Bookplate and every previously unlocked edge variant selectable.

### Moonlit Cover Edge evolution

| Tier / relic count | Existing reading milestone | Cover Edge treatment |
| --- | --- | --- |
| Common / 1 of 6 | 5 minutes | No Cover Edges yet; show Archivist's Quill as a relic. |
| Uncommon / 2 of 6 | 1 hour | No Cover Edges yet; standard Bookplate becomes available. |
| Rare / 3 of 6 | 5 hours | Initial usable style: four clean silver corner brackets. |
| Epic / 4 of 6 | 15 hours | Add fine connecting rules and a small crescent crest. |
| Legendary / 5 of 6 | 40 hours | Add restrained double-corner accents and small star marks. |
| Mythic / 6 of 6 | 100 hours | Add a slow, subtle constellation-trace animation within the decorative margin; a still variant is also available. |

These are cosmetic variants earned with the six existing relic milestones, not extra relics or additional earning requirements. The proposed 2/3/6 cadence remains: standard Bookplate, initial Cover Edges, then the prestige pair. Exact visual assets and motion timing are still being developed for review.

Mythic flavor text: *Every page opens a door. None is the last.*

At 20 minutes per day, the three later relics arrive at approximately 45, 120 and 300 reading days. These are illustrative pacing calculations, not a reading target.

The last stretch is deliberately long-term: at that pace the reader could finish the Atlas at day 180 and wait until day 300 for the final Archive relic. Do not promise frequent rewards indefinitely. Intermediate variants are a possible later expansion, not part of this 18-relic draft.

## Set 2 — Wayfarer's Atlas

**Theme:** maps, parchment, leather, blue-green enamel and journeys into unfamiliar worlds. **Path:** cumulative qualifying reading days, which need not be consecutive.

| Rarity | Relic | Unlock milestone |
| --- | --- | --- |
| Common | Trail Ribbon | 1 qualifying reading day |
| Uncommon | Pocket Compass | 3 qualifying reading days |
| Rare | Cartographer's Lens | 7 qualifying reading days |
| Epic | Map of Hidden Roads | 21 qualifying reading days |
| Legendary | Atlas of Distant Realms | 60 qualifying reading days |
| Mythic | Compass of Every Horizon | 180 qualifying reading days |

- **Bookplate, 2 of 6:** a mapmaker's label with a compass rose and a route line.
- **Cover Edges, 3 of 6:** fine contour lines and compass markers concentrated at the corners.
- **Prestige pair, 6 of 6:** an ornate atlas label and a horizon-and-constellation edge design. Both standard versions remain available.

Mythic flavor text: *A thousand roads begin between two covers.*

Someone who reads three days each week can complete this set over time without maintaining a daily streak. A travel or night-reading theme never requires location or time-of-day tracking.

## Set 3 — Hearthkeeper's Collection

**Theme:** copper, amber glass, carved wood and the warm ritual of returning to a book. **Path:** best-ever reading streak **or** cumulative qualifying days. Either route earns the identical relic.

| Rarity | Relic | Unlock milestone — either route |
| --- | --- | --- |
| Common | First Spark | Best streak of 3 days OR 10 total qualifying days |
| Uncommon | Keeper's Candle | Best streak of 7 days OR 25 total qualifying days |
| Rare | Hearth Seal | Best streak of 14 days OR 50 total qualifying days |
| Epic | Phoenix Feather | Best streak of 30 days OR 100 total qualifying days |
| Legendary | Lantern of the Long Watch | Best streak of 60 days OR 180 total qualifying days |
| Mythic | The Everlasting Hearth | Best streak of 100 days OR 365 total qualifying days |

- **Bookplate, 2 of 6:** a copper-framed label with a candle and wax seal.
- **Cover Edges, 3 of 6:** copper filigree with small ember motifs at the corners.
- **Prestige pair, 6 of 6:** a detailed hearth crest and a phoenix-inspired edge design. Both standard versions remain available.

Mythic flavor text: *The story keeps a place for your return.*

Show both milestone routes in a compact detail view. There is no lesser-status label for the cumulative route. A missed day changes the current streak; it never removes a relic, a best-streak achievement or a decoration.

## Cover customization

The reader may independently select **one relic seal, one Bookplate and one Cover Edge style**. Every slot also has a None option. Sets can be mixed, and selecting a new collectible does not automatically replace an equipped decoration. Shape, detail and Mythic motion belong to the chosen style and tier; color is a separate appearance choice.

### Presentation modes

1. **Cover-preserving margin — default:** fit the full original cover inside an added decorative margin. Put the edges, optional relic seal and Bookplate in that margin rather than over the book's title or artwork. The displayed cover may become slightly smaller, so preview the fit before applying. A Bookplate is a quiet ownership label with a modest border and small set symbol; its default text is "Ex Libris" and "My library", with an optional chosen name or short inscription.
2. **Inset overlay — opt-in:** draw the unlocked Cover Edges inside a bounded inset of the existing cover, with an optional Bookplate badge. This is a separate rendering layer; the original image is not recolored, edited or flattened into a decorated replacement. Inset bounds are a geometric layout constraint, not proof that the area is free of cover text, faces or important art. A badge or line can obscure content. Offer an explicit preview and easy return to the margin layout; do not claim automatic text or subject detection.
3. **Standalone:** use a chosen relic-family template background instead of the original cover image. Keep the same set geometry, tier progression and available decoration slots. Include the book's title and author, with an optional Bookplate and relic seal, in a clear layout. No original cover image is required; this mode also works when one is missing or the reader prefers the standalone design.

These are appearance modes, not separate rewards or earning paths. Default to the cover-preserving margin. The original EPUB/PDF and imported cover image remain unchanged. Preserve the original cover's colors and pixels in cover-based modes: no cover-wide tint, color wash, recoloring filter or destructive export. The opt-in overlay can cover part of the rendered image, but it never changes the underlying cover file.

### Adaptive appearance — implemented approach

The owner requires decoration to be complementary to any book-cover color. Here **complementary means visually harmonious**, not automatically choosing the opposite hue on a color wheel. Adapt the color treatment while keeping the approved subtle forms, tier details and motion recognizable.

This is a plan for a future local appearance algorithm, not an implemented or universally verified color system:

1. Inspect the cover's broad palette and the local background where each border, corner, crest and optional badge will actually sit. Consider lightness, color intensity and variation in those areas, using the rendered layout and matte for the margin mode. A whole-cover average alone is insufficient for a dark corner on a mostly light cover or a busy multicolor border.
2. Propose a restrained accent derived from a compatible dominant or secondary cover hue. Limit saturation and tonal contrast so the decoration supports the art. Keep the original set palette available as an explicit choice; rarity recognition remains the cabinet's written label and badge rather than a forced outside hue.
3. Check contrast against the local backgrounds along the intended decoration. Choose a light or dark tonal version of the accent as appropriate. If the sampled colors are unstable or the edge contains competing high-contrast regions, fall back to a light metallic neutral or dark ink-like neutral. A thin opposing keyline can separate a decorative stroke from a busy background. Keep any optional badge backing local to the badge, never a wash across the whole cover.
4. In standalone mode, evaluate the chosen family-template background and the title/author/Bookplate regions with the same contrast logic. Separate metadata areas from decoration so the presentation remains readable without an imported cover.
5. Preview the result on the actual selected cover, including its corners and Bookplate region. Manual overrides remain available when the automatic suggestion is unsatisfactory. Arbitrary artwork can defeat a simple palette choice; the design does not guarantee universal visibility, harmony or protection of title text.

The earlier in-conversation palette demonstration records intended behavior rather than the shipping algorithm. Native palette checks cover light, dark, saturated, monochrome, busy and high-contrast samples and readable opaque Bookplates. Those samples do not prove universal visibility on arbitrary artwork; owner inspection on the physical exterior screen remains necessary.

Anchor ornaments to the actual fitted cover rectangle, preserving the original aspect ratio without stretching or cropping. Clamp line weight and corner size for narrow or unusually shaped covers. Sample the projected regions beneath the ornament, including the actual matte in margin mode. Recompute and cache the appearance for a cover revision, presentation-mode change or layout change; do not resample or change the palette on every Mythic animation frame. Existing global and exterior-edge palette helpers can supply harmony seeds, while local ornament-footprint checks still need to be designed for app integration.

### Appearance choices and transitions

Remember presentation mode, selected decorations and appearance choices per book, with an optional global default:

- **Auto Harmonize:** derive a harmonious palette and local contrast treatment from the selected cover or standalone background.
- **Light:** use the light neutral treatment.
- **Dark:** use the dark neutral treatment.
- **Original Set:** keep the selected relic family's original color treatment.
- **Manual Accent:** use a chosen accent color, while still previewing its local contrast and allowing a suitable keyline or fallback treatment.

Remember the manual accent separately so switching away and back restores the chosen color. Do not store an edited copy of the original cover as an appearance preference. Keep previous unlocked tier variants, including standard and prestige finishes, selectable; selecting another palette never changes the tier, unlock state or earning history.

When a book or its cover changes, allow one brief, smooth palette transition after the new colors are chosen. Do not repeatedly resample or adjust colors per animation frame; avoid brightness or hue pumping while a book remains open. Reduced-motion preferences use an immediate palette change. Changes to tier geometry and normal appearance controls remain direct, deliberate choices rather than continuous automatic motion.

Switch the opaque Bookplate backing and its text color together. Cross-fading opposite text/background pairs can briefly erase contrast at the midpoint. Border colors may ease into place independently.

### Mythic motion and fallbacks

Only Mythic Cover Edges may animate outside. For Moonlit Archive, the effect remains a slow, subtle constellation trace within the selected decoration bounds: the added margin in the default layout, the bounded inset in overlay mode, or the matching edge region on the standalone template. It does not animate or recolor the book image, relic symbol or Bookplate.

The trace uses the adaptive accent and a restrained contrast difference from its static edge, with the same local keyline/fallback policy. Adaptive appearance changes its color treatment only; it does not increase speed, enlarge the path or add dramatic glow to compensate for a difficult cover. Provide a visible on/off choice and a selectable still version.

Respect reduced-motion preferences by using the still version. Pause animation when the exterior presentation is hidden or inactive, the screen is off, or the app leaves the active reading presentation. Use a static fallback wherever animation is unavailable. Actual exterior behavior, performance and battery effects remain unverified.

A separate brief, optional, reduced-motion-aware unlock flourish belongs in the cabinet or library after reading, with sound off by default. The first release needs consistent, bounded layouts rather than arbitrary sticker dragging, overlap or scaling controls.

## Earning and collection flow

1. Credit the existing tracker: foreground EPUB and PDF reading time, its current idle cutoff and qualifying days of at least five cumulative minutes. These measurements approximate reading activity; they do not prove a book was read.
2. Match the supported stored history against the milestones on first use. Award eligible pieces together and show one collection-ready summary. Do not make existing readers start over.
3. Grant each stable relic ID once, permanently. Grant set rewards automatically at 2, 3 and 6 collected pieces; no separate claim step or expiry. For Moonlit Archive, the already-unlocked Cover Edges also gain their Epic and Legendary variants at the existing 4th and 5th relic milestones; these variants add no new earning requirement.
4. Surface new awards after returning to the library. Queue overlapping awards into one summary instead of interrupting reading with repeated dialogs.
5. Cabinet detail shows the artwork, name, rarity, exact requirement and progress. An unlocked item's detail can show its earned date when known. Retroactive awards use an honest "Earned from earlier reading" label rather than an invented historical unlock date.
6. Offer a clear preview before applying cover decorations. A locked appearance can be previewed, with its requirement visible, but cannot be equipped.

Guaranteed milestones are the starting design. Random drops, duplicates, crafting currencies, expiring seasons and paid boosts are outside this draft. Page flips, last-page visits and imported-book counts are not earning rules. Completed-book, chapter, per-book time and long-session rewards need data and semantics that the current tracker does not preserve.

## Persistence and boundaries

Earned records need stable IDs and versioned rules, including set-finish unlocks. Reward recalculation may add newly eligible pieces; a later rule change or incomplete restored history must not revoke a stored award.

The implemented local backup includes reading-day history, earned relics and appearance choices alongside the existing book metadata. Earlier metadata-only backups remain accepted. History restores by maximum time per date, earned items remain permanent, and retained history floors let new reading count immediately after missing-history recovery. Current explicit appearance choices, including a reset to global inheritance, take priority over restored choices. Each file replacement is atomic; restoring several files is retryable rather than one all-or-nothing transaction. The app remains offline, with no account or server validation.

Enable decorations can be turned off without erasing collected pieces or selected appearance settings. Re-enabling restores the chosen treatment. The library uses a quiet collection count; awards do not interrupt reading with dialogs or sound. Reader features and accessibility controls remain available independently of rewards.

## Approved implementation and remaining review

Moonlit Archive uses the six names and thresholds above, Bookplate at 1 hour, Rare edges at 5 hours, evolved Epic/Legendary edges at 15/40 hours, and animated Mythic edges plus the prestige Bookplate at 100 hours. Earlier variants stay selectable. The default is disabled decorations; enabling uses cover-preserving margin placement unless the reader explicitly chooses inset overlay or standalone.

1. Open **Relic cabinet** below Reading Streak in the library, or from Settings → Outside cover.
2. Preview a tier, then choose global default or a book. Choose layout, color treatment, edges, Bookplate, seal and optional Mythic motion.
3. Changes preview live on the outside screen while the cabinet is open. Keep the phone unfolded and accept Android's display prompt if offered. If the display is unavailable, the cabinet shows its status and **Start outside preview** lets you retry.
4. Tap **Save appearance** to retain a choice, or **Done** to discard the draft. Locked pieces can be previewed but cannot normally be saved. A per-book appearance can be reset to the global default. Closing the cabinet ends its temporary outside preview, restoring an existing reader cover with its original deadline when applicable.

### Temporary owner testing

The debug/QA cabinet offers **Unlock all tiers for 24 hours**, including animated Mythic edges on the actual outside display. Testing uses a separate local appearance profile: reading time, genuine collected rewards, permanent appearance choices and library backups stay unchanged. **Save appearance** during testing saves to that temporary profile. **End testing**, or expiry after 24 hours, restores the genuine profile. Reopening the cabinet does not extend the window. The owner-requested install can explicitly enable this mode and open the cabinet.

The inner preview follows the outside display's proportions, with a narrow portrait fallback when no exterior metrics exist. Margin and standalone layouts fit a centered book-shaped composition, keeping the side rails and corners close to the book instead of the tall screen's extremes. The original cover remains fully fitted in margin mode. The outer view respects display cutouts and system-bar insets, and Mythic's moving trace has a thin contrasting keyline to remain visible over mixed cover colors.

Remaining design work covers the other two sets. Physical exterior appearance, performance and battery use need owner observation of this new implementation; earlier owner confirmation of PDF outside-cover display remains separate.

## Historical design verification

The following records describe the earlier concept previews. Native implementation evidence is recorded separately below.

This draft follows a read-only inspection of the existing tracker: stored daily time, qualifying-day flags and best/current streak support the proposed milestone inputs for EPUB and PDF. These are design and data-availability findings, not newly run app tests. No app code, installation or device tests were performed for this design work.


### Full Moonlit Archive concept preview verification (2026-11-01)

The earlier full-set interactive preview covers all six exact names and milestones. These checks establish that preview only; they do not establish completion of the later adaptive appearance, inset overlay or standalone work. Browser checks verified each staged appearance: no decorations at Common, Bookplate at Uncommon, corners at Rare, lines and crest at Epic, added star/corner accents at Legendary, and margin motion plus prestige Bookplate at Mythic. The animated traveler changed position during playback (260.219 px to 72.7656 px); Pause held its position at 70.0156 px across separate observations and Play resumed the running state. The Bookplate toggle worked, and the 320 px viewport had equal client/scroll widths, with no horizontal overflow. Reduced-motion and document-hidden fallbacks are present in the preview source; those host preference/visibility transitions were not separately exercised. No app, device, performance or battery validation is implied.

At the initial read-only design review, the outside presentation used a static ImageView. The review identified the need for a separate decoration layer, measured margin/inset/template bounds, local palette and contrast handling, and animator teardown on Stop, screen-off, timeout, session end and controller close. Preserve the existing cover deadline and full-image fit. That historical inspection did not implement or verify an adaptive algorithm or a new presentation mode.


### Adaptive preview verification (2026-11-01)

The separate adaptive-appearance demonstration uses real canvas pixel sampling from generated synthetic covers. Browser checks covered Forest, Crimson, Ivory, Night, Ocean and busy monochrome, plus custom black, white, mid-gray, bright yellow, blue and magenta. Auto selected cover-related pale/deep tones; busy monochrome retained a neutral accent. Controlled opaque Bookplate foreground/background contrast was at least 12.1:1 in these samples. The primary stroke alone had weak contrast over some busy pixels, confirming the need for the opposing keyline or added margin rather than a universal visibility claim.

A full-canvas checksum stayed `7ba0ace2` while light/dark/set/auto treatments, tier, margin placement and decoration visibility changed on the same Forest sample. The source artwork was not repainted or filtered by those controls. Standalone rendered its own title/author template and selected relic label, with separate Common/Mythic canvas checksums. The Bookplate text/backing transition issue found during review was corrected: the pair switches together while only the border interpolates. The 320px viewport had equal client/scroll widths with no horizontal overflow. The demonstration remains a heuristic and design preview, not the shipping app algorithm or phone validation.


## Native implementation validation (2026-11-01)

The implementation contains the permanent local collection, cabinet/editor, local palette and contrast handling, full-fit margin/inset/standalone rendering, and a cached static cover with an optional 10-second Mythic trace. Motion honors system animation settings and pauses on hidden/clipped/detached views, host backgrounding and presentation teardown without renewing the existing cover deadline. Original publication and cover files are unchanged.

Implementation `a2d2384` and native-test follow-up `242409d` passed the guarded build, **369/369 JVM checks**, debug/QA lint and **16/16 isolated Android checks** with zero skipped. Tests used task-owned read-only `emulator-5556`, generated fixtures and the bundled sample books. They exercised backup roundtrip/idempotency/corruption safety, original pixel preservation, margin/inset/standalone drawing, real moving Mythic pixels with cached-cover reuse, visibility/teardown pauses, locked choices, global/per-book Save/reset and 200% text labels and activation. A short-window native preview was also inspected during integration. Relic tiles are repeatable labeled buttons, so an already selected item can still apply its preview preset.

Native motion evidence recorded system animations enabled, phase advancing from `0.0037` to `0.0404`, changed rendered pixels, unchanged preparation count, a held hidden phase, clipping pause/resume and terminal release. These are native-view checks, not physical exterior-display evidence.

Task: `moonlit-f44b4cc347ee7e18`. Source snapshot: `bb597ebb8162185d74e816e93f508e4eb68b73bb20e1a1432f434bbbefeab821`. Combined source-bound evidence under `%LOCALAPPDATA%\BookCoverBuild\tasks\moonlit-f44b4cc347ee7e18\evidence\`:
`bb597ebb8162185d74e816e93f508e4eb68b73bb20e1a1432f434bbbefeab821-a82e9cf14f8348d2900c4c9c039bf4f9.json`.

Initial APK: `BookCover-moonlit-f44b4cc347ee7e18.apk`; SHA-256 `47ab645728f82c229b9b0c68d33e2f0cdb1f5602bab9385490849c49259b47dd`. Installed in place on the owner's phone on 2026-11-01 at their request; installed APK readback matched and the original installation time was unchanged. The generated APK, receipts and screenshots remain outside Git. Exterior appearance, performance and battery use still require owner observation; previous PDF outside-display confirmation remains recorded separately. The testing/live-preview follow-up has its separate validation record in the active backlog.
