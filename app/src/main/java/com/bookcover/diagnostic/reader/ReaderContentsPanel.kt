package com.bookcover.diagnostic.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.SavedBookmarks
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Revealed controls only: the quiet reading page retains its single whole-book percentage. */
@Composable
internal fun ChapterProgressSummary(status: ChapterStatus?) {
    val section = status?.currentTocIndex == null
    val unit = if (section) "section" else "chapter"
    val unitLabel = if (section) "Section" else "Chapter"
    Column(Modifier.fillMaxWidth().testTag("chapter-summary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(status?.title ?: unitLabel, style = MaterialTheme.typography.titleSmall)
        val progress = status?.progression?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        if (progress != null) {
            val percent = (progress * 100).toInt()
            Text("$percent% of $unit", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("chapter-progress").semantics { contentDescription = "$unitLabel completion $percent percent" })
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        } else {
            Text("$unitLabel progress unavailable", style = MaterialTheme.typography.bodySmall)
        }
        if (status != null) {
            val learning = status.remainingMinutes == null && status.bookRemainingMinutes == null
            Text(when {
                progress == null -> "Time left unavailable for this $unit"
                learning -> learningText(status.pagesUntilEstimate)
                else -> timeLeftText(status.remainingMinutes, unit)
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chapter-time-left"))
            if (!learning) {
                Text(timeLeftText(status.bookRemainingMinutes, "book"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("book-time-left"))
            }
        }
    }
}

/** Shows that learning is progressing; only page turns at a reading pace count. */
internal fun learningText(pages: Int): String = when {
    pages <= 1 -> "Time left: learning your reading pace (about 1 more page)"
    else -> "Time left: learning your reading pace (about $pages more pages)"
}

/** Whole minutes up to an hour, then hours and minutes, so long books stay readable. */
internal fun timeLeftText(minutes: Int?, unit: String): String = when {
    minutes == null -> "Time left in $unit unavailable"
    minutes <= 0 -> "Less than a minute left in $unit"
    minutes == 1 -> "About 1 minute left in $unit"
    minutes < 60 -> "About $minutes minutes left in $unit"
    minutes % 60 == 0 -> "About ${minutes / 60} h left in $unit"
    else -> "About ${minutes / 60} h ${minutes % 60} min left in $unit"
}

/** A bounded list avoids composing long TOCs offscreen and opens directly on the current entry. */
@Composable
internal fun ReaderContentsPanel(
    session: EpubSession,
    record: BookRecord,
    onNavigate: () -> Unit,
    onRename: (raw: String, initialName: String) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
) {
    val currentIndex = session.chapterStatus?.currentTocIndex
    val entries = session.contentsEntries
    val firstItem = currentIndex?.takeIf { it in entries.indices }?.plus(1) ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstItem)
    LaunchedEffect(currentIndex, entries.size) {
        if (currentIndex != null && currentIndex in entries.indices) {
            // Item 0 is the chapter/contents header, followed by one item per TOC entry.
            listState.scrollToItem(currentIndex + 1)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().testTag("reader-contents-scroll"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "contents-header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChapterProgressSummary(session.chapterStatus)
                Text("Contents", style = MaterialTheme.typography.titleLarge)
                if (entries.isEmpty()) Text("This book does not provide a table of contents.")
            }
        }
        itemsIndexed(entries, key = { index, _ -> "contents-$index" }) { index, entry ->
            val current = index == currentIndex
            TextButton(onClick = { session.goToContents(index); onNavigate() },
                colors = ButtonDefaults.textButtonColors(containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(start = (entry.depth.coerceAtMost(4) * 12).dp)
                    .testTag("contents-entry-$index").semantics { selected = current }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title)
                    if (current) Text("Current chapter", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("current-chapter-marker"))
                }
            }
        }
        item(key = "bookmarks-header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
                if (record.bookmarks.isEmpty()) Text("No bookmarks yet.")
            }
        }
        itemsIndexed(record.bookmarks, key = { index, _ -> "bookmark-$index" }) { index, raw ->
            val locator = remember(raw) { runCatching { Locator.fromJSON(JSONObject(raw)) }.getOrNull() }
            val name = remember(raw) { SavedBookmarks.name(raw) }
            val chapter = remember(raw) { SavedBookmarks.chapterTitle(raw) }
            val label = name ?: chapter ?: "Saved bookmark"
            val percentage = locator?.locations?.totalProgression?.takeIf { it.isFinite() }
                ?.let { "${(it.coerceIn(0.0, 1.0) * 100).toInt()}%" }
            Row(Modifier.fillMaxWidth().testTag("bookmark-item-$index"), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { session.goToBookmark(raw); onNavigate() }, enabled = locator != null,
                    modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label)
                        val context = listOfNotNull(chapter?.takeUnless { it == label }, percentage).joinToString(" · ")
                        if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column {
                    TextButton(onClick = { onRename(raw, label) }, enabled = locator != null,
                        modifier = Modifier.testTag("rename-bookmark-$index").semantics { contentDescription = "Rename bookmark $label" }) { Text("Rename") }
                    TextButton(onClick = { onRemove(raw) },
                        modifier = Modifier.testTag("remove-bookmark-$index")
                            .semantics { contentDescription = "Remove bookmark $label" }) { Text("Remove") }
                }
            }
        }
        item(key = "contents-footer") { TextButton(onClick = onClose) { Text("Back to controls") } }
    }
}

internal data class BookmarkDraft(val existingValue: String?, val initialName: String)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun BookmarkNameDialog(draft: BookmarkDraft, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(draft) { mutableStateOf(draft.initialName.take(SavedBookmarks.MAX_NAME_LENGTH)) }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("bookmark-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(if (draft.existingValue == null) "Add bookmark" else "Rename bookmark") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(SavedBookmarks.MAX_NAME_LENGTH) }, singleLine = true,
                label = { Text("Bookmark name") }, modifier = Modifier.fillMaxWidth().testTag("bookmark-name"),
                supportingText = { Text("${name.length}/${SavedBookmarks.MAX_NAME_LENGTH}") })
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank(),
                modifier = Modifier.testTag("bookmark-save")) { Text("Save bookmark") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
