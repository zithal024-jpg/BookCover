package com.bookcover.diagnostic.rewards

internal enum class MoonlitTier(val id: String, val title: String, val rarity: String, val thresholdMillis: Long) {
    COMMON("quill", "Archivist’s Quill", "Common", 5 * 60_000L),
    UNCOMMON("lamp", "Midnight Lamp", "Uncommon", 60 * 60_000L),
    RARE("key", "Silver Key", "Rare", 5 * 60 * 60_000L),
    EPIC("orrery", "Orrery of Margins", "Epic", 15 * 60 * 60_000L),
    LEGENDARY("grimoire", "Moonbound Grimoire", "Legendary", 40 * 60 * 60_000L),
    MYTHIC("archive", "The Endless Archive", "Mythic", 100 * 60 * 60_000L);

    companion object { fun fromId(id: String?): MoonlitTier? = entries.firstOrNull { it.id == id } }
}

internal enum class RelicMode { MARGIN, OVERLAY, STANDALONE }
internal enum class RelicColorMode { AUTO, LIGHT, DARK, SET, CUSTOM }
internal enum class RelicBookplate { NONE, STANDARD, PRESTIGE }

internal enum class RelicSet(val id: String, val title: String) {
    MOONLIT("moonlit", "Moonlit Archive"),
    WAYFARER("atlas", "Wayfarer’s Atlas"),
    HEARTHKEEPER("hearth", "Hearthkeeper’s Collection");

    companion object { fun fromId(id: String?): RelicSet? = entries.firstOrNull { it.id == id } }
}

internal data class RelicReadingStats(val totalMillis: Long, val qualifyingDays: Int, val bestStreak: Int)

/** Stable collectible identity is separate from its shared rarity and its selected drawing style. */
internal data class RelicDefinition(
    val set: RelicSet,
    val tier: MoonlitTier,
    val id: String,
    val title: String,
    val requiredDays: Int = 0,
    val requiredStreak: Int = 0,
) {
    val requirementLabel: String get() = when (set) {
        RelicSet.MOONLIT -> if (tier.thresholdMillis < 3_600_000L)
            "${tier.thresholdMillis / 60_000} total reading minutes" else
            "${tier.thresholdMillis / 3_600_000} total reading ${if (tier.thresholdMillis == 3_600_000L) "hour" else "hours"}"
        RelicSet.WAYFARER -> "$requiredDays qualifying reading ${if (requiredDays == 1) "day" else "days"}"
        RelicSet.HEARTHKEEPER -> "Best streak of $requiredStreak days OR $requiredDays total qualifying days"
    }

    fun eligible(stats: RelicReadingStats): Boolean = when (set) {
        RelicSet.MOONLIT -> stats.totalMillis >= tier.thresholdMillis
        RelicSet.WAYFARER -> stats.qualifyingDays >= requiredDays
        RelicSet.HEARTHKEEPER -> stats.bestStreak >= requiredStreak || stats.qualifyingDays >= requiredDays
    }

    fun eligible(collection: RelicCollection): Boolean = eligible(collection.readingStats)

    fun progressLabel(collection: RelicCollection): String = when (set) {
        RelicSet.MOONLIT -> "${collection.totalMillis / 60_000} credited reading minutes · ${tier.thresholdMillis / 60_000} needed"
        RelicSet.WAYFARER -> "${collection.qualifyingDays} of $requiredDays qualifying days"
        RelicSet.HEARTHKEEPER -> "Best streak: ${collection.bestStreak}/$requiredStreak days · Total: ${collection.qualifyingDays}/$requiredDays qualifying days"
    }
}

internal object RelicCatalog {
    val all: List<RelicDefinition> = MoonlitTier.entries.map { tier ->
        RelicDefinition(RelicSet.MOONLIT, tier, tier.id, tier.title)
    } + listOf(
        RelicDefinition(RelicSet.WAYFARER, MoonlitTier.COMMON, "atlas_ribbon", "Trail Ribbon", 1),
        RelicDefinition(RelicSet.WAYFARER, MoonlitTier.UNCOMMON, "atlas_compass", "Pocket Compass", 3),
        RelicDefinition(RelicSet.WAYFARER, MoonlitTier.RARE, "atlas_lens", "Cartographer’s Lens", 7),
        RelicDefinition(RelicSet.WAYFARER, MoonlitTier.EPIC, "atlas_roads", "Map of Hidden Roads", 21),
        RelicDefinition(RelicSet.WAYFARER, MoonlitTier.LEGENDARY, "atlas_realms", "Atlas of Distant Realms", 60),
        RelicDefinition(RelicSet.WAYFARER, MoonlitTier.MYTHIC, "atlas_horizon", "Compass of Every Horizon", 180),
        RelicDefinition(RelicSet.HEARTHKEEPER, MoonlitTier.COMMON, "hearth_spark", "First Spark", 10, 3),
        RelicDefinition(RelicSet.HEARTHKEEPER, MoonlitTier.UNCOMMON, "hearth_candle", "Keeper’s Candle", 25, 7),
        RelicDefinition(RelicSet.HEARTHKEEPER, MoonlitTier.RARE, "hearth_seal", "Hearth Seal", 50, 14),
        RelicDefinition(RelicSet.HEARTHKEEPER, MoonlitTier.EPIC, "hearth_feather", "Phoenix Feather", 100, 30),
        RelicDefinition(RelicSet.HEARTHKEEPER, MoonlitTier.LEGENDARY, "hearth_lantern", "Lantern of the Long Watch", 180, 60),
        RelicDefinition(RelicSet.HEARTHKEEPER, MoonlitTier.MYTHIC, "hearth_everlasting", "The Everlasting Hearth", 365, 100),
    )
    private val byId = all.associateBy { it.id }
    fun forSet(set: RelicSet): List<RelicDefinition> = all.filter { it.set == set }
    fun item(set: RelicSet, tier: MoonlitTier): RelicDefinition = all.first { it.set == set && it.tier == tier }
    fun fromId(id: String?): RelicDefinition? = byId[id]
}

/** Appearance only: the original cover and publication files are never modified. */
internal data class RelicAppearance(
    val enabled: Boolean = false,
    val mode: RelicMode = RelicMode.MARGIN,
    val colorMode: RelicColorMode = RelicColorMode.AUTO,
    val customAccent: Int = 0xffb9b2cf.toInt(),
    val edgeTier: MoonlitTier? = null,
    val bookplate: RelicBookplate = RelicBookplate.NONE,
    val seal: MoonlitTier? = null,
    val motion: Boolean = true,
    val inscription: String = "My library",
    val edgeSet: RelicSet = RelicSet.MOONLIT,
    val sealSet: RelicSet = RelicSet.MOONLIT,
) {
    fun permittedRelics(unlocked: Set<RelicDefinition>): RelicAppearance = copy(
        customAccent = customAccent or 0xff000000.toInt(),
        edgeTier = edgeTier?.takeIf { it.ordinal >= MoonlitTier.RARE.ordinal && RelicCatalog.item(edgeSet, it) in unlocked },
        // Retained only to decode old appearance records. Bookplates no longer render or equip.
        bookplate = RelicBookplate.NONE,
        seal = seal?.takeIf { RelicCatalog.item(sealSet, it) in unlocked },
        inscription = inscription.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(80).ifBlank { "My library" },
    )

    /** Compatibility for earlier Moonlit-only callers and stored rarity values. */
    fun permitted(unlocked: Set<MoonlitTier>): RelicAppearance =
        permittedRelics(unlocked.map { RelicCatalog.item(RelicSet.MOONLIT, it) }.toSet())
}

internal data class RelicCollection(
    val totalMillis: Long = 0,
    /** Stable item ID -> earned time; zero denotes credit from earlier reading. */
    val earned: Map<String, Long> = emptyMap(),
    val seen: Set<String> = emptySet(),
    val defaultAppearance: RelicAppearance = RelicAppearance(),
    val bookAppearances: Map<String, RelicAppearance> = emptyMap(),
    val qualifyingDays: Int = 0,
    val bestStreak: Int = 0,
) {
    val readingStats: RelicReadingStats get() = RelicReadingStats(totalMillis, qualifyingDays, bestStreak)
    val unlockedRelics: Set<RelicDefinition> get() = earned.keys.mapNotNull(RelicCatalog::fromId).toSet()
    val unseenRelics: Set<RelicDefinition> get() = unlockedRelics.filter { it.id !in seen }.toSet()
    val unlocked: Set<MoonlitTier> get() = earned.keys.mapNotNull(MoonlitTier::fromId).toSet()
    val unseen: Set<MoonlitTier> get() = unlocked.filter { it.id !in seen }.toSet()
    fun appearanceForRelics(bookId: String?, availableRelics: Set<RelicDefinition> = unlockedRelics): RelicAppearance =
        (bookId?.let(bookAppearances::get) ?: defaultAppearance).permittedRelics(availableRelics)
    fun appearanceFor(bookId: String?, availableTiers: Set<MoonlitTier> = unlocked): RelicAppearance =
        (bookId?.let(bookAppearances::get) ?: defaultAppearance).permitted(availableTiers)
}
