package com.bookcover.diagnostic.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import com.bookcover.diagnostic.R
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.DEFAULT_COVER_SEED
import com.bookcover.diagnostic.library.readerControlsColorScheme
import com.bookcover.diagnostic.ui.SettingsSection
import com.bookcover.diagnostic.ui.SettingsSwitchRow
import kotlinx.coroutines.delay
import org.readium.r2.navigator.R as NavigatorR

/** Page colour for a reading theme; also used for the plain screen shown while a book reopens. */
internal fun readerBackground(theme: String?): Color =
    when (theme) { "Night" -> Color.Black; "Sepia" -> Color(0xFFFAF4E8); else -> Color.White }

/** H3: one short sheet at a time above the compact reader bar, so most of the page stays visible. */
private enum class ReaderSheet { NONE, TEXT, COVER, MORE }

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun EpubReaderScreen(
    session: EpubSession,
    record: BookRecord,
    coverActive: Boolean,
    coverBusy: Boolean,
    stopPending: Boolean,
    canStartCover: Boolean,
    message: String,
    onStartCover: () -> Unit,
    onStopCover: () -> Unit,
    onHome: () -> Unit,
    onChooseCover: () -> Unit,
    onSaveBookmark: (name: String, existingValue: String?) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    libraryTheme: String = "automatic",
    coverSeed: Int = DEFAULT_COVER_SEED,
) {
    var contentsVisible by remember(session) { mutableStateOf(false) }
    var sheet by remember(session) { mutableStateOf(ReaderSheet.NONE) }
    var bookmarkDraft by remember(session) { mutableStateOf<BookmarkDraft?>(null) }
    var footerHeight by remember(session) { mutableStateOf(48.dp) }
    // Readium keeps its own bottom margin inside reflowable pages; the progress row sits in that margin
    // instead of reserving a second band below it. Fixed-layout pages have no such margin.
    val pageMargin = if (session.supportsTypography) dimensionResource(NavigatorR.dimen.readium_navigator_epub_vertical_padding) else 0.dp
    val density = LocalDensity.current
    val overlayVisible = session.controlsVisible || contentsVisible || bookmarkDraft != null
    val night = session.themeName == "Night"
    val background = readerBackground(session.themeName)
    // One palette with the library: same accent, light or dark to match the page.
    val scheme = readerControlsColorScheme(libraryTheme, coverSeed, night)
    // Hiding the controls (page tap, page button, search result) also closes any open sheet.
    LaunchedEffect(session.controlsVisible) { if (!session.controlsVisible) sheet = ReaderSheet.NONE }
    // The first line is the app's own message (bookmark saved, an error); the rest is cover status,
    // shown only on the Cover sheet. A new message appears briefly as a banner above the bar.
    val notice = message.substringBefore('\n').trim()
    var shownNotice by remember(session) { mutableStateOf<String?>(null) }
    var firstNotice by remember(session) { mutableStateOf(true) }
    LaunchedEffect(notice) {
        if (firstNotice) { firstNotice = false; return@LaunchedEffect }
        if (notice.isEmpty()) return@LaunchedEffect
        shownNotice = notice
        delay(5_000)
        shownNotice = null
    }
    BackHandler {
        when {
            contentsVisible -> contentsVisible = false
            sheet != ReaderSheet.NONE -> sheet = ReaderSheet.NONE
            session.controlsVisible -> session.controlsVisible = false
            else -> onHome()
        }
    }
    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
            val screenHeight = maxHeight
            val screenWidth = maxWidth
            Surface(color = background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    val pageBottom = (footerHeight - pageMargin).coerceAtLeast(0.dp)
                    AndroidView(
                        factory = { context ->
                            // PT-1: the touch layer decides about swipes before Readium's views see them.
                            val container = FragmentContainerView(context).apply {
                                id = R.id.epub_reader_container
                                doOnAttach { post { session.attach(this) } }
                            }
                            PageTurnTouchLayer(context, container).apply { gestures = session.pageTurns }
                        },
                        update = {
                            // Keep screen-reader focus in the revealed controls instead of the book behind them.
                            it.importantForAccessibility = if (overlayVisible) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            it.descendantFocusability = if (overlayVisible) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
                            if (overlayVisible && it.hasFocus()) it.clearFocus()
                            it.gestures = session.pageTurns
                            if (it.isAttachedToWindow) session.attach(it.container)
                        },
                        modifier = Modifier.fillMaxSize().padding(bottom = pageBottom),
                    )
                    // Same bounds as the reader; the progress row below draws above it.
                    PageTurnOverlay(session.pageTurns, background,
                        bottomClip = if (overlayVisible) 0.dp else minOf(footerHeight, pageMargin),
                        modifier = Modifier.fillMaxSize().padding(bottom = pageBottom))
                    if (!overlayVisible) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(min = 48.dp)
                        .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
                        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${(session.progress * 100).toInt().coerceIn(0, 100)}%",
                            color = if (night) Color(0xFFBCBDB5) else Color(0xFF555E53),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = "Book completion ${(session.progress * 100).toInt()} percent"
                            })
                        TextButton(onClick = { session.controlsVisible = true },
                            modifier = Modifier.testTag("reader-show-controls").heightIn(min = 48.dp)
                                .semantics { contentDescription = "Show reading controls" }) { Text("Controls") }
                    }
                }
            }
            if (session.controlsVisible || contentsVisible) {
                // An open sheet replaces the top bar and the button row, so the page it adjusts stays in view.
                if (sheet == ReaderSheet.NONE || contentsVisible) Surface(tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp).testTag("reader-top-bar")) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onHome) { Text("Library") }
                        Text(record.title, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = session::openSearch, enabled = !session.typographyUpdating,
                            modifier = Modifier.testTag("open-search").heightIn(min = 48.dp)) { Text("Search") }
                        TextButton(onClick = { session.controlsVisible = false; contentsVisible = false },
                            modifier = Modifier.testTag("reader-hide-controls").heightIn(min = 48.dp)) { Text("Read") }
                    }
                }
                if (contentsVisible) {
                    Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                        .heightIn(max = maxHeight * 0.75f)) {
                        ReaderContentsPanel(
                            session = session, record = record,
                            onNavigate = { contentsVisible = false; session.controlsVisible = false },
                            onRename = { value, initialName -> bookmarkDraft = BookmarkDraft(value, initialName) },
                            onRemove = onRemoveBookmark,
                            onClose = { contentsVisible = false },
                        )
                    }
                } else {
                    Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                        .testTag("reader-controls-panel")) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            shownNotice?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("reader-notice")
                                        .semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            if (sheet != ReaderSheet.NONE) {
                                // Whole panel at most about 40% of the screen: the sheet's own heading and
                                // padding take roughly 88dp, and the rest scrolls.
                                ReaderSheetFrame(sheet, (screenHeight * 0.4f - 88.dp).coerceAtLeast(96.dp), onClose = { sheet = ReaderSheet.NONE }) {
                                    when (sheet) {
                                        ReaderSheet.TEXT -> TextSheet(session)
                                        ReaderSheet.COVER -> CoverSheet(coverActive, coverBusy, stopPending, canStartCover,
                                            message.substringAfter('\n', "").trim(), onStartCover, onStopCover, onChooseCover)
                                        ReaderSheet.MORE -> MoreSheet(session)
                                        ReaderSheet.NONE -> Unit
                                    }
                                }
                            } else {
                                Box(Modifier.padding(horizontal = 8.dp)) { ChapterProgressSummary(session.chapterStatus) }
                                val coverLabel = when { stopPending -> "Stopping…"; coverActive -> "Cover on"; coverBusy -> "Starting…"; else -> "Cover off" }
                                val coverState = when { stopPending -> "stopping"; coverActive -> "on"; coverBusy -> "starting"; else -> "off" }
                                ReaderBar(screenWidth, LocalDensity.current.fontScale.coerceAtLeast(1f)) {
                                    BarButton("Contents", "open-contents", enabled = !session.typographyUpdating) { contentsVisible = true }
                                    BarButton("Aa", "reader-text-settings", description = "Text size and page colour", serif = true) { sheet = ReaderSheet.TEXT }
                                    BarButton("Bookmark", "add-bookmark",
                                        enabled = !session.typographyUpdating && session.currentLocator != null) {
                                        bookmarkDraft = BookmarkDraft(null, session.chapterStatus?.title
                                            ?: session.currentLocator?.title ?: "Bookmark")
                                    }
                                    BarButton(coverLabel, "reader-cover", description = "Outside cover, $coverState") { sheet = ReaderSheet.COVER }
                                    BarButton("More", "reader-more", description = "More reading options") { sheet = ReaderSheet.MORE }
                                }
                            }
                        }
                    }
                }
            }
        }
        bookmarkDraft?.let { draft ->
            BookmarkNameDialog(draft = draft, onDismiss = { bookmarkDraft = null }, onSave = { name ->
                onSaveBookmark(name, draft.existingValue)
                bookmarkDraft = null
            })
        }
        ReaderSearchDialog(controller = session.search, onSelect = { session.goToSearchResult(it) },
            onClose = session.search::dismiss)
    }
}

/** Five equal buttons in a row; with large system text they wrap onto more rows instead of clipping. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReaderBar(width: Dp, fontScale: Float, content: @Composable FlowRowScope.() -> Unit) {
    val perRow = (width / (68.dp * fontScale)).toInt().coerceIn(2, 5)
    FlowRow(Modifier.fillMaxWidth().testTag("reader-bar"), maxItemsInEachRow = perRow,
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun FlowRowScope.BarButton(
    label: String,
    tag: String,
    description: String? = null,
    enabled: Boolean = true,
    serif: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag).semantics {
            description?.let { contentDescription = it }
        }) {
        Text(label, textAlign = TextAlign.Center, maxLines = 2,
            style = if (serif) MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                else MaterialTheme.typography.labelLarge)
    }
}

/** A short, scrollable sheet with a heading and Done; keeps the "reader-controls-scroll" tag tests scroll by. */
@Composable
private fun ReaderSheetFrame(sheet: ReaderSheet, maxHeight: Dp, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val title = when (sheet) { ReaderSheet.TEXT -> "Text and page"; ReaderSheet.COVER -> "Outside cover"; else -> "More options" }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("reader-sheet-close")) { Text("Done") }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("reader-controls-scroll")
            .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Aa: page colour and text size first; the book-formatting switch and custom formatting below. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TextSheet(session: EpubSession) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Paper", "Sepia", "Night").forEach { name ->
            FilterChip(selected = session.themeName == name, onClick = { session.changeTheme(name) },
                modifier = Modifier.testTag("reading-theme-$name").semantics { contentDescription = "$name page colour" },
                label = { Text(name) })
        }
    }
    if (!session.supportsTypography) {
        Text("Fixed-layout EPUB: the publisher positions the page contents. Text size, font, spacing, margin and alignment changes are unavailable for this book.",
            style = MaterialTheme.typography.bodySmall)
    }
    val canChangeType = session.supportsTypography && !session.typographyUpdating
    val percent = ReadingTextSize.percent(session.fontScale)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { session.changeFont(session.fontScale - 0.1) },
            enabled = canChangeType && percent > ReadingTextSize.MIN_PERCENT,
            modifier = Modifier.testTag("text-size-decrease").semantics { contentDescription = "Decrease text size" }) { Text("A−") }
        Text("Text size: $percent%", modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { session.changeFont(session.fontScale + 0.1) },
            enabled = canChangeType && percent < ReadingTextSize.MAX_PERCENT,
            modifier = Modifier.testTag("text-size-increase").semantics { contentDescription = "Increase text size" }) { Text("A+") }
    }
    var draftSize by remember(session.fontScale) { mutableFloatStateOf(session.fontScale.toFloat()) }
    Slider(value = draftSize, onValueChange = { draftSize = it },
        onValueChangeFinished = { if (draftSize.toDouble() != session.fontScale) session.changeFont(draftSize.toDouble()) },
        valueRange = 0.8f..2.2f, steps = 13, enabled = canChangeType,
        modifier = Modifier.testTag("text-size-slider").semantics { contentDescription = "EPUB text size" })
    SettingsSwitchRow("Publisher styles", session.comfort.publisherStyles, session::changePublisherStyles,
        modifier = Modifier.testTag("publisher-styles"), enabled = canChangeType,
        summary = "Keep the book's fonts, spacing, indents and alignment. Text size still applies.")
    var customOpen by remember(session) { mutableStateOf(!session.comfort.publisherStyles) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("custom-formatting")
        .toggleable(value = customOpen, role = Role.Button, onValueChange = { customOpen = it })
        .semantics { stateDescription = if (customOpen) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically) {
        Text("Custom formatting", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(if (customOpen) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (customOpen) {
        if (session.comfort.publisherStyles && session.supportsTypography) {
            Text("These replace the book's own formatting. Turn off publisher styles to use them; your saved choices are kept.",
                style = MaterialTheme.typography.bodySmall)
        }
        val typeEnabled = canChangeType && !session.comfort.publisherStyles
        Text("Font", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingFont.entries.forEach { font ->
                FilterChip(selected = session.fontId == font.id, onClick = { session.changeFontFamily(font.id) },
                    enabled = typeEnabled, label = { Text(font.label) })
            }
        }
        Text("Line spacing", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingComfort.lineSpacingChoices.forEach { spacing ->
                FilterChip(selected = session.comfort.lineSpacing == spacing,
                    onClick = { session.changeLineSpacing(spacing) }, enabled = typeEnabled,
                    label = { Text("${spacing}×") },
                    modifier = Modifier.semantics { contentDescription = "Line spacing $spacing" })
            }
        }
        Text("Side margins", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingMargins.entries.forEach { margin ->
                FilterChip(selected = session.comfort.margins == margin, onClick = { session.changePageMargins(margin.id) },
                    enabled = typeEnabled, label = { Text(margin.label) })
            }
        }
        Text("Alignment", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingAlignment.entries.forEach { alignment ->
                FilterChip(selected = session.comfort.alignment == alignment,
                    onClick = { session.changeTextAlignment(alignment.id) }, enabled = typeEnabled,
                    label = { Text(alignment.label) })
            }
        }
    }
    if (session.typographyUpdating) Text("Reflowing text and restoring your reading position…", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CoverSheet(
    coverActive: Boolean,
    coverBusy: Boolean,
    stopPending: Boolean,
    canStartCover: Boolean,
    status: String,
    onStartCover: () -> Unit,
    onStopCover: () -> Unit,
    onChooseCover: () -> Unit,
) {
    Text(when { stopPending -> "Stopping outside cover…"; coverActive -> "Outside cover on"; coverBusy -> "Starting outside cover…"; else -> "Outside cover off" },
        style = MaterialTheme.typography.labelLarge)
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 3)
    if (coverBusy) OutlinedButton(onClick = onStopCover, enabled = !stopPending, modifier = Modifier.fillMaxWidth()) { Text("Stop outer cover") }
    else OutlinedButton(onClick = onStartCover, enabled = canStartCover, modifier = Modifier.fillMaxWidth()) { Text("Start outer cover") }
    TextButton(onClick = onChooseCover, modifier = Modifier.heightIn(min = 48.dp).testTag("choose-cover")) {
        Text("Choose cover image (stops outside cover)")
    }
}

/** Page turning options, plus page buttons for anyone who cannot swipe or tap the edges. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MoreSheet(session: EpubSession) {
    SettingsSection("Page turning") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Edge-tap page turns", style = MaterialTheme.typography.labelLarge)
                Text("Optional narrow edges follow the book's reading direction. Swipes always work.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = session.edgeTaps, onCheckedChange = session::changeEdgeTaps,
                modifier = Modifier.semantics { contentDescription = "Enable edge-tap page turns" })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("volume-key-page-turns")
            .semantics { contentDescription = "Volume-key page turns" }
            .toggleable(value = session.volumeKeys, role = Role.Switch, onValueChange = session::changeVolumeKeys),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Volume-key page turns", style = MaterialTheme.typography.labelLarge)
                Text("Volume down: next page. Volume up: previous page. Off keeps normal volume control.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = session.volumeKeys, onCheckedChange = null)
        }
        SettingsSwitchRow("Paper page turn", session.paperPageTurn, session::changePaperPageTurn,
            modifier = Modifier.testTag("paper-page-turn"),
            summary = "Pages peel from the edge like paper and follow your finger. Off uses a plain slide.")
        Text("Keyboard: Page Down and Page Up follow book order. Left and right arrows follow the book's reading direction. Keys turn pages while controls are closed.",
            style = MaterialTheme.typography.bodySmall)
    }
    SettingsSection("Page buttons", showDivider = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { session.previous() }, enabled = !session.typographyUpdating,
                modifier = Modifier.testTag("reader-previous-page").heightIn(min = 48.dp)) { Text("Previous page") }
            TextButton(onClick = { session.next() }, enabled = !session.typographyUpdating,
                modifier = Modifier.testTag("reader-next-page").heightIn(min = 48.dp)) { Text("Next page") }
        }
    }
}
