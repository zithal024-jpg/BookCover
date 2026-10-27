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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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

/** Page colour for a reading theme; also used for the plain screen shown while a book reopens. */
internal fun readerBackground(theme: String?): Color =
    when (theme) { "Night" -> Color.Black; "Sepia" -> Color(0xFFFAF4E8); else -> Color.White }

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
    var bookmarkDraft by remember(session) { mutableStateOf<BookmarkDraft?>(null) }
    var footerHeight by remember(session) { mutableStateOf(48.dp) }
    val density = LocalDensity.current
    val overlayVisible = session.controlsVisible || contentsVisible || bookmarkDraft != null
    val night = session.themeName == "Night"
    val background = readerBackground(session.themeName)
    // One palette with the library: same accent, light or dark to match the page.
    val scheme = readerControlsColorScheme(libraryTheme, coverSeed, night)
    BackHandler {
        when { contentsVisible -> contentsVisible = false; session.controlsVisible -> session.controlsVisible = false; else -> onHome() }
    }
    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
            Surface(color = background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            FragmentContainerView(context).apply {
                                id = R.id.epub_reader_container
                                doOnAttach { post { session.attach(this) } }
                            }
                        },
                        update = {
                            // Keep screen-reader focus in the revealed controls instead of the book behind them.
                            it.importantForAccessibility = if (overlayVisible) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            it.descendantFocusability = if (overlayVisible) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
                            if (overlayVisible && it.hasFocus()) it.clearFocus()
                            if (it.isAttachedToWindow) session.attach(it)
                        },
                        modifier = Modifier.fillMaxSize().padding(bottom = footerHeight),
                    )
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
                Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onHome) { Text("Library") }
                        Text(record.title, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { session.controlsVisible = false; contentsVisible = false }) { Text("Read") }
                    }
                }
                Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                    .heightIn(max = maxHeight * 0.75f)) {
                    if (contentsVisible) {
                        ReaderContentsPanel(
                            session = session, record = record,
                            onNavigate = { contentsVisible = false; session.controlsVisible = false },
                            onRename = { value, initialName -> bookmarkDraft = BookmarkDraft(value, initialName) },
                            onRemove = onRemoveBookmark,
                            onClose = { contentsVisible = false },
                        )
                    } else {
                        Column(Modifier.testTag("reader-controls-scroll").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(record.title, style = MaterialTheme.typography.titleMedium)
                            ChapterProgressSummary(session.chapterStatus)
                            Text(when { stopPending -> "Stopping outside cover…"; coverActive -> "Outside cover on"; coverBusy -> "Starting outside cover…"; else -> "Outside cover off" },
                                style = MaterialTheme.typography.labelLarge)
                            Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            if (coverBusy) OutlinedButton(onClick = onStopCover, enabled = !stopPending, modifier = Modifier.fillMaxWidth()) { Text("Stop outer cover") }
                            else OutlinedButton(onClick = onStartCover, enabled = canStartCover, modifier = Modifier.fillMaxWidth()) { Text("Start outer cover") }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { contentsVisible = true }, enabled = !session.typographyUpdating,
                                    modifier = Modifier.testTag("open-contents")) { Text("Contents") }
                                OutlinedButton(onClick = {
                                    bookmarkDraft = BookmarkDraft(null, session.chapterStatus?.title
                                        ?: session.currentLocator?.title ?: "Bookmark")
                                }, enabled = !session.typographyUpdating && session.currentLocator != null,
                                    modifier = Modifier.testTag("add-bookmark")) { Text("Bookmark") }
                                TextButton(onClick = { session.previous() }, enabled = !session.typographyUpdating,
                                    modifier = Modifier.testTag("reader-previous-page").heightIn(min = 48.dp)) { Text("Previous page") }
                                TextButton(onClick = { session.next() }, enabled = !session.typographyUpdating,
                                    modifier = Modifier.testTag("reader-next-page").heightIn(min = 48.dp)) { Text("Next page") }
                            }
                            SettingsSection("Text") {
                                if (!session.supportsTypography) {
                                    Text("Fixed-layout EPUB: the publisher positions the page contents. Font, spacing, margin and alignment changes are unavailable for this book.", style = MaterialTheme.typography.bodySmall)
                                }
                                val canChangeType = session.supportsTypography && !session.typographyUpdating
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Publisher styles", style = MaterialTheme.typography.labelLarge)
                                        Text("Use the book's fonts and paragraph formatting.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = session.comfort.publisherStyles, onCheckedChange = session::changePublisherStyles,
                                        enabled = canChangeType, modifier = Modifier.semantics { contentDescription = "Use publisher styles" })
                                }
                                if (session.comfort.publisherStyles && session.supportsTypography) {
                                    Text("Turn off publisher styles to use the custom settings below. Your saved choices are kept.", style = MaterialTheme.typography.bodySmall)
                                }
                                val typeEnabled = canChangeType && !session.comfort.publisherStyles
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    OutlinedButton(onClick = { session.changeFont(session.fontScale - 0.1) },
                                        enabled = typeEnabled && ReadingTextSize.percent(session.fontScale) > ReadingTextSize.MIN_PERCENT,
                                        modifier = Modifier.semantics { contentDescription = "Decrease text size" }) { Text("A−") }
                                    Text("${if (session.comfort.publisherStyles) "Saved text size" else "Text size"}: ${ReadingTextSize.percent(session.fontScale)}%",
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
                                    OutlinedButton(onClick = { session.changeFont(session.fontScale + 0.1) },
                                        enabled = typeEnabled && ReadingTextSize.percent(session.fontScale) < ReadingTextSize.MAX_PERCENT,
                                        modifier = Modifier.semantics { contentDescription = "Increase text size" }) { Text("A+") }
                                }
                                var draftSize by remember(session.fontScale) { mutableFloatStateOf(session.fontScale.toFloat()) }
                                Slider(value = draftSize, onValueChange = { draftSize = it },
                                    onValueChangeFinished = { if (draftSize.toDouble() != session.fontScale) session.changeFont(draftSize.toDouble()) },
                                    valueRange = 0.8f..2.2f, steps = 13, enabled = typeEnabled,
                                    modifier = Modifier.semantics { contentDescription = "EPUB text size" })
                                ReadingFont.entries.forEach { font ->
                                    FilterChip(selected = session.fontId == font.id, onClick = { session.changeFontFamily(font.id) },
                                        enabled = typeEnabled, label = { Text(font.label) })
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
                                if (session.typographyUpdating) Text("Reflowing text and restoring your reading position…", style = MaterialTheme.typography.bodySmall)
                            }
                            SettingsSection("Page turning", showDivider = true) {
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
                                Text("Keyboard: Page Down and Page Up follow book order. Left and right arrows follow the book's reading direction. Keys turn pages while controls are closed.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            SettingsSection("Page colour", showDivider = true) {
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Paper", "Sepia", "Night").forEach { name ->
                                        FilterChip(selected = session.themeName == name, onClick = { session.changeTheme(name) },
                                            modifier = Modifier.testTag("reading-theme-$name"), label = { Text(name) })
                                    }
                                }
                            }
                            HorizontalDivider()
                            TextButton(onClick = onChooseCover) { Text("Choose cover image (stops outside cover)") }
                            Button(onClick = { session.controlsVisible = false }, modifier = Modifier.fillMaxWidth()) { Text("Resume reading") }
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
    }
}
