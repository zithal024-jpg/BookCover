package com.bookcover.diagnostic.reader

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.fragment.app.FragmentContainerView
import com.bookcover.diagnostic.R
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.DEFAULT_COVER_SEED
import com.bookcover.diagnostic.library.SavedBookmarks
import com.bookcover.diagnostic.library.readerControlsColorScheme
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import kotlin.math.abs

/** PDF pages retain their original layout and can be zoomed using the native pinch gesture. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun PdfReaderScreen(
    session: PdfSession,
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
    var bookmarksVisible by remember(session) { mutableStateOf(false) }
    var coverVisible by remember(session) { mutableStateOf(false) }
    var bookmarkDraft by remember(session) { mutableStateOf<BookmarkDraft?>(null) }
    val overlayVisible = session.controlsVisible || bookmarksVisible || coverVisible || bookmarkDraft != null
    BackHandler {
        when {
            bookmarkDraft != null -> bookmarkDraft = null
            bookmarksVisible -> bookmarksVisible = false
            coverVisible -> coverVisible = false
            session.controlsVisible -> session.controlsVisible = false
            else -> onHome()
        }
    }
    MaterialTheme(colorScheme = readerControlsColorScheme(libraryTheme, coverSeed, false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
            AndroidView(
                factory = { context -> FragmentContainerView(context).apply {
                    id = R.id.epub_reader_container
                    doOnAttach { post { session.attach(this) } }
                } },
                update = {
                    it.importantForAccessibility = if (overlayVisible || session.textMode) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    it.descendantFocusability = if (overlayVisible || session.textMode) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
                    if ((overlayVisible || session.textMode) && it.hasFocus()) it.clearFocus()
                    if (it.isAttachedToWindow) session.attach(it)
                },
                modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
            )
            if (session.textMode) {
                PdfTextReadingSurface(session, modifier = Modifier.fillMaxSize().padding(bottom = 48.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${(session.progress * 100).toInt().coerceIn(0, 100)}%", modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "Book completion ${(session.progress * 100).toInt().coerceIn(0, 100)} percent" })
                TextButton(onClick = { session.controlsVisible = true }, modifier = Modifier.testTag("pdf-show-controls")) { Text("Controls") }
            }
            if (session.controlsVisible && !bookmarksVisible && !coverVisible) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onHome) { Text("Library") }
                        Text(record.title, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { session.controlsVisible = false }) { Text("Read") }
                    }
                }
                Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text(if (session.textMode) "Text view may omit pictures and formatting; some lines can repeat."
                            else "PDF pages keep their original layout. Pinch to zoom.", style = MaterialTheme.typography.bodySmall)
                        if (session.textMode) Text("Swipe sideways or use Previous and Next to read.",
                            style = MaterialTheme.typography.bodySmall)
                        message.substringBefore('\n').trim().takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = session::previous, modifier = Modifier.testTag("pdf-previous")) { Text("Previous") }
                            TextButton(onClick = session::next, modifier = Modifier.testTag("pdf-next")) { Text("Next") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { bookmarksVisible = true }) { Text("Bookmarks") }
                            TextButton(onClick = { coverVisible = true }) { Text("Cover") }
                            TextButton(onClick = { session.changeTextMode(!session.textMode) },
                                modifier = Modifier.testTag("pdf-text-toggle")) {
                                Text(if (session.textMode) "Original pages" else "Text view")
                            }
                        }
                        if (session.textMode) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { session.changeTextScale(session.textScale - 0.1) },
                                    enabled = ReadingTextSize.percent(session.textScale) > ReadingTextSize.MIN_PERCENT,
                                    modifier = Modifier.testTag("pdf-text-size-decrease")
                                        .semantics { contentDescription = "Decrease text size" }) { Text("A−") }
                                Text("${ReadingTextSize.percent(session.textScale)}%")
                                OutlinedButton(onClick = { session.changeTextScale(session.textScale + 0.1) },
                                    enabled = ReadingTextSize.percent(session.textScale) < ReadingTextSize.MAX_PERCENT,
                                    modifier = Modifier.testTag("pdf-text-size-increase")
                                        .semantics { contentDescription = "Increase text size" }) { Text("A+") }
                            }
                        }
                    }
                }
            }
            if (bookmarksVisible) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(12.dp).heightIn(max = maxHeight * 0.75f)) {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { bookmarkDraft = BookmarkDraft(null, "Bookmark") },
                                enabled = session.currentLocator != null && (!session.textMode || session.textViewState is PdfTextViewState.Ready)) { Text("Add bookmark") }
                            if (record.bookmarks.isEmpty()) Text("No bookmarks yet.")
                        }
                        itemsIndexed(record.bookmarks, key = { index, _ -> "bookmark-$index" }) { _, raw ->
                            val locator = remember(raw) { runCatching { Locator.fromJSON(JSONObject(raw)) }.getOrNull() }
                            val name = remember(raw) { SavedBookmarks.name(raw) ?: "Saved bookmark" }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { session.goToBookmark(raw); bookmarksVisible = false },
                                    enabled = locator != null, modifier = Modifier.weight(1f)) { Text(name) }
                                TextButton(onClick = { bookmarkDraft = BookmarkDraft(raw, name) }, enabled = locator != null) { Text("Rename") }
                                TextButton(onClick = { onRemoveBookmark(raw) }) { Text("Remove") }
                            }
                        }
                        item { TextButton(onClick = { bookmarksVisible = false }) { Text("Back to controls") } }
                    }
                }
            }
            if (coverVisible) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Outside cover", style = MaterialTheme.typography.titleMedium)
                        Text(message.substringAfter('\n', "").trim(), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            if (coverActive || coverBusy || stopPending) TextButton(onClick = onStopCover, enabled = !stopPending) {
                                Text(if (stopPending) "Stopping…" else "Stop")
                            } else TextButton(onClick = onStartCover, enabled = canStartCover) { Text("Start") }
                            TextButton(onClick = onChooseCover) { Text("Change cover") }
                            TextButton(onClick = { coverVisible = false }) { Text("Done") }
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

internal data class PdfTextWindow(val text: String, val starts: List<Int>)

/** Join pages without creating extra blank lines, keeping each source offset reversible. */
internal fun pdfTextWindow(pages: List<PdfTextWindowPage>): PdfTextWindow {
    val starts = ArrayList<Int>(pages.size)
    val text = buildString {
        pages.forEachIndexed { index, page ->
            if (index > 0 && !endsWith("\n") && !page.text.startsWith("\n")) append('\n')
            starts.add(length)
            append(page.text)
        }
    }
    return PdfTextWindow(text, starts)
}

/** Keep the PDF navigator attached underneath so text and original pages share one page locator. */
@Composable
private fun PdfTextReadingSurface(session: PdfSession, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val dragThreshold = with(density) { 64.dp.toPx() }
    val background = MaterialTheme.colorScheme.background
    val gestureModifier = Modifier
        .pointerInput(session) {
            detectTapGestures {
                session.textActivity()
                session.controlsVisible = !session.controlsVisible
            }
        }
        .pointerInput(session, dragThreshold) {
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f; session.textActivity() },
                onHorizontalDrag = { change, amount -> distance += amount; change.consume() },
                onDragEnd = {
                    if (abs(distance) >= dragThreshold) {
                        val forward = if (session.textRightToLeft) distance > 0 else distance < 0
                        if (forward) session.next() else session.previous()
                    }
                    distance = 0f
                },
                onDragCancel = { distance = 0f },
            )
        }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction("Next text page") { session.next(); true },
                CustomAccessibilityAction("Previous text page") { session.previous(); true },
            )
        }
    BoxWithConstraints(modifier.background(background).then(gestureModifier)) {
        LaunchedEffect(maxWidth, maxHeight, session.textScale) { session.clearTextTurnHistory() }
        when (val state = session.textViewState) {
            PdfTextViewState.Loading -> Text("Loading text…", Modifier.align(Alignment.Center))
            PdfTextViewState.Unavailable -> PdfTextUnavailable(
                "Text view needs Android 15 or newer.", session, Modifier.align(Alignment.Center))
            PdfTextViewState.Empty -> PdfTextUnavailable(
                "This page has no selectable text. Open the original page to see its contents.",
                session, Modifier.align(Alignment.Center))
            PdfTextViewState.Failed -> PdfTextUnavailable(
                "Text on this page could not be displayed. Open the original page to keep reading.",
                session, Modifier.align(Alignment.Center))
            is PdfTextViewState.Ready -> {
                val availableWidth = (maxWidth - 48.dp).coerceAtLeast(1.dp).coerceAtMost(800.dp)
                val availableHeight = (maxHeight - 48.dp).coerceAtLeast(1.dp)
                val widthPx = with(density) { availableWidth.roundToPx().coerceAtLeast(1) }
                val heightPx = with(density) { availableHeight.toPx().coerceAtLeast(1f) }
                val scale = session.textScale
                val style = LocalTextStyle.current.merge(TextStyle(
                    fontFamily = FontFamily.Serif, fontSize = 20.sp * scale.toFloat(),
                    lineHeight = 30.sp * scale.toFloat(), color = MaterialTheme.colorScheme.onBackground))
                val measurer = rememberTextMeasurer()
                val window = remember(state.pages) { pdfTextWindow(state.pages) }
                val text = window.text
                val starts = window.starts
                val measured = remember(measurer, text, widthPx, style) {
                    measurer.measure(text = text, style = style,
                        constraints = Constraints(maxWidth = widthPx))
                }

                // Every window has page boundaries on explicit line breaks, so
                // prepending or dropping neighboring pages cannot change wrapping.
                fun anchorAt(index: Int): Pair<Int, Int> {
                    val selected = starts.indexOfLast { it <= index }.coerceAtLeast(0)
                    val source = state.pages[selected]
                    return source.page to (index - starts[selected]).coerceIn(0, source.text.length)
                }
                val sourceIndex = state.pages.indexOfFirst { it.page == state.page }.coerceAtLeast(0)
                val source = state.pages[sourceIndex]
                var sourceOffset = if (session.textOffset == Int.MAX_VALUE)
                    (source.text.length - 1).coerceAtLeast(0)
                else session.textOffset.coerceIn(0, (source.text.length - 1).coerceAtLeast(0))
                // A leading newline can be the separator ending the preceding page's
                // final line. Start at its first following character in that case.
                if (sourceIndex > 0 && sourceOffset == 0 && source.text.startsWith("\n") &&
                    !state.pages[sourceIndex - 1].text.endsWith("\n")) sourceOffset = 1
                val absolute = (starts[sourceIndex] + sourceOffset)
                    .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                var firstLine = measured.getLineForOffset(absolute)

                // A previous turn through the beginning of the bounded window
                // enters the preceding page at its final full screenful.
                if (session.textOffset == Int.MAX_VALUE) {
                    val finalLine = firstLine
                    while (firstLine > 0 &&
                        measured.getLineBottom(finalLine) - measured.getLineTop(firstLine - 1) <= heightPx) firstLine--
                    val normalized = anchorAt(measured.getLineStart(firstLine))
                    LaunchedEffect(state.page, normalized) {
                        session.normalizeTextAnchor(normalized.first, normalized.second)
                    }
                }
                var lastLine = firstLine
                while (lastLine + 1 < measured.lineCount &&
                    measured.getLineBottom(lastLine + 1) - measured.getLineTop(firstLine) <= heightPx) lastLine++
                val start = measured.getLineStart(firstLine)
                val end = measured.getLineEnd(lastLine, visibleEnd = false).coerceIn(start, text.length)
                val visible = text.substring(start, end)
                val previous = if (firstLine > 0) {
                    var candidate = firstLine - 1
                    while (candidate > 0 &&
                        measured.getLineBottom(firstLine - 1) - measured.getLineTop(candidate - 1) <= heightPx) candidate--
                    anchorAt(measured.getLineStart(candidate))
                } else null
                val next = if (lastLine + 1 < measured.lineCount &&
                    measured.getLineStart(lastLine + 1) < text.length)
                    anchorAt(measured.getLineStart(lastLine + 1)) else null

                DisposableEffect(session, state, previous, next) {
                    session.textTurnHandler = { forward ->
                        val target = if (forward) next else previous
                        if (target != null) session.moveTextAnchor(target.first, target.second, forward)
                        else {
                            val boundary = if (forward) state.pages.last().page + 1 else state.pages.first().page - 1
                            if (boundary in 1..state.pageCount)
                                session.moveTextAnchor(boundary, if (forward) 0 else Int.MAX_VALUE, forward)
                        }
                    }
                    onDispose { session.textTurnHandler = null }
                }
                Text(visible, modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 24.dp).width(availableWidth).testTag("pdf-text-content"),
                    style = style)
            }
        }
    }
}

@Composable
private fun PdfTextUnavailable(message: String, session: PdfSession, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        TextButton(onClick = { session.changeTextMode(false) }) { Text("Original pages") }
    }
}
