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
) {
    fun permitted(unlocked: Set<MoonlitTier>): RelicAppearance = copy(
        customAccent = customAccent or 0xff000000.toInt(),
        edgeTier = edgeTier?.takeIf { it.ordinal >= MoonlitTier.RARE.ordinal && it in unlocked },
        bookplate = when {
            bookplate == RelicBookplate.PRESTIGE && MoonlitTier.MYTHIC !in unlocked ->
                if (MoonlitTier.UNCOMMON in unlocked) RelicBookplate.STANDARD else RelicBookplate.NONE
            bookplate == RelicBookplate.STANDARD && MoonlitTier.UNCOMMON !in unlocked -> RelicBookplate.NONE
            else -> bookplate
        },
        seal = seal?.takeIf { it in unlocked },
        inscription = inscription.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(80).ifBlank { "My library" },
    )
}

internal data class RelicCollection(
    val totalMillis: Long = 0,
    /** Stable item ID -> earned time; zero denotes credit from earlier reading. */
    val earned: Map<String, Long> = emptyMap(),
    val seen: Set<String> = emptySet(),
    val defaultAppearance: RelicAppearance = RelicAppearance(),
    val bookAppearances: Map<String, RelicAppearance> = emptyMap(),
) {
    val unlocked: Set<MoonlitTier> get() = earned.keys.mapNotNull(MoonlitTier::fromId).toSet()
    val unseen: Set<MoonlitTier> get() = unlocked.filter { it.id !in seen }.toSet()
    fun appearanceFor(bookId: String?): RelicAppearance =
        (bookId?.let(bookAppearances::get) ?: defaultAppearance).permitted(unlocked)
}
