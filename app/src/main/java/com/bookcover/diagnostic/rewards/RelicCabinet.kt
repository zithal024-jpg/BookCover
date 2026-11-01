package com.bookcover.diagnostic.rewards

import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookcover.diagnostic.cover.CoverArtwork
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.ui.SettingsSection
import com.bookcover.diagnostic.ui.SettingsSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A local collection and an explicit-save appearance editor. Locked pieces may be inspected in the
 * preview, but Save refuses any locked choice. Persistence acknowledgement belongs to the caller.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RelicCabinet(
    collection: RelicCollection,
    books: List<BookRecord>,
    library: BookLibrary,
    onSave: (String?, RelicAppearance) -> Unit,
    onReset: (String) -> Unit,
    onSeen: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    var targetId by remember { mutableStateOf<String?>(null) }
    var draft by remember(targetId) { mutableStateOf(collection.appearanceFor(targetId)) }
    var selectedTier by remember { mutableStateOf(collection.unlocked.maxByOrNull { it.ordinal } ?: MoonlitTier.COMMON) }
    var customHex by remember(targetId, draft.customAccent) {
        mutableStateOf(String.format(Locale.ROOT, "#%06X", draft.customAccent and 0xFFFFFF))
    }
    val newRelics = remember { collection.unseen }
    LaunchedEffect(Unit) { onSeen() }
    val targetBook = books.firstOrNull { it.id == targetId }
    val previewBook = targetBook ?: books.maxByOrNull { maxOf(it.lastReadAt, it.addedAt) }
    val missingTarget = targetId != null && targetBook == null
    val artwork by produceState<CoverArtwork?>(null, previewBook?.id, previewBook?.coverRevision) {
        value = null
        previewBook?.let { book ->
            value = withContext(Dispatchers.IO) {
                // Thumbnail decoding is bounded, and the library owns its shared bitmap cache.
                runCatching { CoverArtwork.from(library.thumbnailBitmap(book)) }.getOrNull()
            }
        }
    }
    val lockedChoices = remember(draft, collection.unlocked) { appearanceHasLockedChoices(draft, collection.unlocked) }
    val validCustom = draft.colorMode != RelicColorMode.CUSTOM || customHex.matches(Regex("#[0-9a-fA-F]{6}"))
    val canSave = !busy && !missingTarget && !lockedChoices && validCustom
    val previewAppearance = if (artwork == null) draft.copy(mode = RelicMode.STANDALONE) else draft
    val previewTitle = previewBook?.title ?: "The Lantern Keeper"
    val previewAuthor = previewBook?.author ?: "BookCover sample"
    val scheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = scheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                // The preview must fit short unfolded windows as well as portrait phones. This is
                // the finite dialog height, rather than the unbounded height of the scrolling column.
                val previewWidth = minOf(300.dp, maxHeight * .48f)
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp).fillMaxSize()
                    .imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("relic-cabinet-scroll"),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Relic cabinet", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                                modifier = Modifier.semantics { heading() })
                            Text("Moonlit Archive · ${collection.unlocked.size} of 6 collected", style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onDismiss, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("relic-cabinet-close")) { Text("Done") }
                    }
                    if (newRelics.isNotEmpty()) Text(
                        (if (newRelics.all { collection.earned[it.id] == 0L }) "Your earlier reading unlocked: " else "New in your collection: ") +
                            newRelics.sortedBy { it.ordinal }.joinToString { it.title },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("relic-award-summary"))
                    Text("${relicReadingTime(collection.totalMillis)} credited across EPUB and PDF reading. Collected relics stay yours.",
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val fontScale = LocalDensity.current.fontScale
                        val columns = when {
                            fontScale >= 1.8f || maxWidth < 300.dp -> 1
                            maxWidth >= 600.dp && fontScale < 1.5f -> 3
                            else -> 2
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MoonlitTier.entries.chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    row.forEach { tier ->
                                        RelicTile(tier, tier in collection.unlocked, selectedTier == tier, !busy,
                                            Modifier.weight(1f)) {
                                            selectedTier = tier
                                            // This is a draft preview only. Save remains gated by earned pieces.
                                            draft = appearanceAtTier(draft, tier)
                                        }
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }

                    SelectedRelic(selectedTier, collection)

                    SettingsSection("Outside cover preview", summary = "Changes are applied only when you tap Save appearance.") {
                        Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = previewWidth).fillMaxWidth()
                            .aspectRatio(2f / 3f).testTag("relic-cover-preview")
                            .semantics {
                                contentDescription = listOf("Outside cover appearance preview", previewTitle, previewAuthor)
                                    .filter { it.isNotBlank() }.joinToString(". ")
                            }) {
                            // Compose owns the preview's single accessible label and bounds. The
                            // separate outer-screen renderer keeps its native accessibility label.
                            AndroidView(
                                factory = { context -> RelicCoverView(context).apply {
                                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                } },
                                update = { view -> view.bind(artwork, previewAppearance, previewTitle, previewAuthor) },
                                onRelease = { it.release() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (artwork == null) Text("A standalone cover is shown until book artwork is available.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        if (lockedChoices) Text("Preview only: one or more selected pieces are locked. Keep reading to unlock them, or choose collected pieces to save.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("relic-locked-preview"))
                    }

                    SettingsSection("Apply to") {
                        CabinetChoice("Appearance target", targetId,
                            listOf(null to "Global default · all books") + books.map { it.id to it.title },
                            enabled = !busy, tag = "relic-target", unavailableLabel = "Book no longer in library") { targetId = it }
                        Text(if (targetId == null) "Used by books without their own appearance."
                            else if (collection.bookAppearances.containsKey(targetId)) "This book has its own appearance."
                            else "This book currently inherits the global default.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        if (missingTarget) Text("This book is no longer in the library. Choose another target before saving.",
                            color = scheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    SettingsSection("Appearance") {
                        SettingsSwitchRow("Enable decorations", draft.enabled, { draft = draft.copy(enabled = it) }, enabled = !busy,
                            modifier = Modifier.testTag("relic-enabled"), summary = "Turning this off keeps your collection and saved choices.")
                        CabinetChoice("Cover layout", draft.mode, listOf(
                            RelicMode.MARGIN to "Keep artwork clear · added margin",
                            RelicMode.OVERLAY to "Inset overlay · may cover edge content",
                            RelicMode.STANDALONE to "Standalone title and author cover"), !busy, "relic-mode") { draft = draft.copy(mode = it) }
                        CabinetChoice("Color treatment", draft.colorMode, listOf(
                            RelicColorMode.AUTO to "Auto · match cover colors",
                            RelicColorMode.LIGHT to "Light stroke",
                            RelicColorMode.DARK to "Dark stroke",
                            RelicColorMode.SET to "Moonlit Archive palette",
                            RelicColorMode.CUSTOM to "Custom color"), !busy, "relic-color") { draft = draft.copy(colorMode = it) }
                        if (draft.colorMode == RelicColorMode.CUSTOM) OutlinedTextField(
                            value = customHex,
                            onValueChange = { entered ->
                                customHex = entered.take(7)
                                if (customHex.matches(Regex("#[0-9a-fA-F]{6}"))) {
                                    draft = draft.copy(customAccent = (customHex.drop(1).toLong(16) or 0xff000000L).toInt())
                                }
                            },
                            label = { Text("Custom color · #RRGGBB") }, enabled = !busy, singleLine = true,
                            isError = !validCustom,
                            supportingText = { Text(if (validCustom) "Preview the color against your cover." else "Enter # followed by six hexadecimal digits.") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth().testTag("relic-custom-color"))
                        CabinetChoice("Cover Edges", draft.edgeTier,
                            listOf(null to "None") + MoonlitTier.entries.filter { it.ordinal >= MoonlitTier.RARE.ordinal }
                                .map { it to relicChoiceLabel(it, collection.unlocked) },
                            !busy, "relic-edges") { draft = draft.copy(edgeTier = it) }
                        CabinetChoice("Bookplate", draft.bookplate, listOf(
                            RelicBookplate.NONE to "None",
                            RelicBookplate.STANDARD to if (MoonlitTier.UNCOMMON in collection.unlocked) "Standard · collected" else "Standard · locked until 1 hour",
                            RelicBookplate.PRESTIGE to if (MoonlitTier.MYTHIC in collection.unlocked) "Prestige · collected" else "Prestige · locked until 100 hours"),
                            !busy, "relic-bookplate") { draft = draft.copy(bookplate = it) }
                        if (draft.bookplate != RelicBookplate.NONE) OutlinedTextField(
                            value = draft.inscription, onValueChange = { draft = draft.copy(inscription = it.take(80)) },
                            label = { Text("Bookplate inscription · optional") },
                            supportingText = { Text("Shown on the outside cover. Leave blank for My library.") },
                            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("relic-inscription"))
                        CabinetChoice("Relic seal", draft.seal,
                            listOf(null to "None") + MoonlitTier.entries.map { it to relicChoiceLabel(it, collection.unlocked) },
                            !busy, "relic-seal") { draft = draft.copy(seal = it) }
                        SettingsSwitchRow("Animate Mythic edges", draft.motion, { draft = draft.copy(motion = it) }, enabled = !busy,
                            modifier = Modifier.testTag("relic-motion"),
                            summary = "Only Mythic edges move. Your phone's reduced-motion setting keeps them static.")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (canSave) onSave(targetId, draft) }, enabled = canSave,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("relic-save")) { Text(if (busy) "Saving…" else "Save appearance") }
                        if (targetId != null) TextButton(onClick = { targetId?.let(onReset) },
                            enabled = !busy && !missingTarget && collection.bookAppearances.containsKey(targetId),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("relic-reset-book")) { Text("Use global default for this book") }
                        Text("Your original book and cover files stay unchanged. Reward history and appearance choices are included in BookCover's local backup.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun RelicTile(tier: MoonlitTier, earned: Boolean, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val color = relicRarityColor(tier, scheme.background.luminance() < .5f)
    Surface(shape = shape, color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        modifier = modifier.clip(shape).border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                semanticsTestTag = "relic-item-${tier.id}"
                role = Role.Button
                contentDescription = "${tier.title}, ${tier.rarity}, ${relicMilestone(tier)}"
                stateDescription = (if (earned) "Collected" else "Locked; available to preview") +
                    if (selected) "; Details shown" else ""
                if (enabled) semanticsOnClick(label = "Preview ${tier.title}") { onClick(); true }
                else disabled()
            }) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RelicGlyph(tier, color, Modifier.size(44.dp))
            Text(tier.title, style = MaterialTheme.typography.titleSmall)
            Text(tier.rarity, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(if (earned) "Collected" else "Locked", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RelicGlyph(tier: MoonlitTier, color: Color, modifier: Modifier) {
    Canvas(modifier.clip(CircleShape).background(color.copy(alpha = .08f)).border(1.dp, color, CircleShape)
        .padding(8.dp).clearAndSetSemantics { }) {
        drawIntoCanvas { canvas -> drawMoonlitGlyph(canvas.nativeCanvas, tier, RectF(0f, 0f, size.width, size.height), color.toArgb()) }
    }
}

@Composable
private fun SelectedRelic(tier: MoonlitTier, collection: RelicCollection) {
    val earned = tier in collection.unlocked
    val earnedAt = collection.earned[tier.id]
    val progress = if (earned) 1f else (collection.totalMillis.toDouble() / tier.thresholdMillis).coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth().testTag("relic-selected-detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tier.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif), modifier = Modifier.semantics { heading() })
        Text("${tier.rarity} · ${relicMilestone(tier)}", style = MaterialTheme.typography.bodyMedium)
        Text(when {
            !earned -> "${relicReadingTime(collection.totalMillis)} credited · locked"
            earnedAt == 0L -> "Collected from earlier reading"
            earnedAt != null && earnedAt > 0L -> "Collected ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(earnedAt))}"
            else -> "Collected"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { progress }, drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Progress toward ${tier.title}" })
    }
}

@Composable
private fun <T> CabinetChoice(label: String, value: T, choices: List<Pair<T, String>>, enabled: Boolean, tag: String,
                              unavailableLabel: String = "Unavailable choice", onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == value }?.second ?: unavailableLabel
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                    .semantics { contentDescription = "$label: $selectedLabel" }) {
                Text(selectedLabel, modifier = Modifier.weight(1f))
                Text("▾", Modifier.padding(start = 8.dp).clearAndSetSemantics { })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                choices.forEach { (choice, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(choice) },
                        enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (choice == value) "Selected" else "Not selected" })
                }
            }
        }
    }
}

private fun appearanceAtTier(appearance: RelicAppearance, tier: MoonlitTier): RelicAppearance = appearance.copy(
    enabled = true,
    seal = tier,
    edgeTier = tier.takeIf { it.ordinal >= MoonlitTier.RARE.ordinal },
    bookplate = when (tier) {
        MoonlitTier.COMMON -> RelicBookplate.NONE
        MoonlitTier.MYTHIC -> RelicBookplate.PRESTIGE
        else -> RelicBookplate.STANDARD
    },
)

private fun appearanceHasLockedChoices(appearance: RelicAppearance, unlocked: Set<MoonlitTier>): Boolean =
    appearance.edgeTier?.let { it !in unlocked } == true || appearance.seal?.let { it !in unlocked } == true ||
        (appearance.bookplate == RelicBookplate.STANDARD && MoonlitTier.UNCOMMON !in unlocked) ||
        (appearance.bookplate == RelicBookplate.PRESTIGE && MoonlitTier.MYTHIC !in unlocked)

private fun relicChoiceLabel(tier: MoonlitTier, unlocked: Set<MoonlitTier>): String =
    "${tier.rarity} · ${tier.title}" + if (tier in unlocked) " · collected" else " · locked"

private fun relicMilestone(tier: MoonlitTier): String = when (tier) {
    MoonlitTier.COMMON -> "5 total reading minutes"
    MoonlitTier.UNCOMMON -> "1 total reading hour"
    MoonlitTier.RARE -> "5 total reading hours"
    MoonlitTier.EPIC -> "15 total reading hours"
    MoonlitTier.LEGENDARY -> "40 total reading hours"
    MoonlitTier.MYTHIC -> "100 total reading hours"
}

private fun relicReadingTime(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    return if (hours == 0L) "$minutes min" else "$hours h ${minutes % 60} min"
}

private fun relicRarityColor(tier: MoonlitTier, dark: Boolean): Color = Color(when (tier) {
    MoonlitTier.COMMON -> if (dark) 0xffc1c9bc else 0xff56654e
    MoonlitTier.UNCOMMON -> if (dark) 0xffa0d68a else 0xff37652b
    MoonlitTier.RARE -> if (dark) 0xff9bbef0 else 0xff325d98
    MoonlitTier.EPIC -> if (dark) 0xffd1abea else 0xff75478f
    MoonlitTier.LEGENDARY -> if (dark) 0xffefb07d else 0xff985322
    MoonlitTier.MYTHIC -> if (dark) 0xffe2ca87 else 0xff856722
})
