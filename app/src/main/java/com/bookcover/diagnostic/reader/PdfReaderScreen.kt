package com.bookcover.diagnostic.reader

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
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
                    it.importantForAccessibility = if (overlayVisible) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    it.descendantFocusability = if (overlayVisible) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
                    if (overlayVisible && it.hasFocus()) it.clearFocus()
                    if (it.isAttachedToWindow) session.attach(it)
                },
                modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
            )
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
                        Text("PDF pages keep their original layout. Pinch to zoom.", style = MaterialTheme.typography.bodySmall)
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
                                enabled = session.currentLocator != null) { Text("Add bookmark") }
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
