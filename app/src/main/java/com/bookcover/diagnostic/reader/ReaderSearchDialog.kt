package com.bookcover.diagnostic.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A modal search surface: it never moves the navigator until the reader chooses a result. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ReaderSearchDialog(
    controller: BookSearchController,
    onSelect: (BookSearchResult) -> Unit,
    onClose: () -> Unit,
) {
    // A replacement book gets a fresh collected value before the first composition of its dialog.
    val state by key(controller) { controller.state.collectAsState() }
    if (!state.visible) return
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val resultsScroll = rememberLazyListState()
    LaunchedEffect(state.query) { resultsScroll.scrollToItem(0) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth().fillMaxHeight(0.94f)
                .safeDrawingPadding().imePadding().padding(12.dp)
                .semantics { testTagsAsResourceId = true; paneTitle = "Search this book" }
                .testTag("search-dialog"),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Search this book", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("search-close")) {
                        Text("Close")
                    }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = controller::updateQuery,
                    modifier = Modifier.fillMaxWidth().testTag("search-query"),
                    label = { Text("Find words or a phrase") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                )
                TextButton(onClick = { controller.updateQuery("") }, enabled = state.query.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-clear")) {
                    Text("Clear search")
                }
                HorizontalDivider()
                LazyColumn(
                    state = resultsScroll,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("search-results"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item(key = "status") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.status == BookSearchStatus.SEARCHING) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(searchStatusText(state), modifier = Modifier.fillMaxWidth().testTag("search-status")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Matches ignore case and accents. Phrases stay within a chapter.",
                                style = MaterialTheme.typography.bodySmall)
                            if (state.status == BookSearchStatus.FAILED) {
                                OutlinedButton(onClick = controller::retry,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-retry")) {
                                    Text("Try again")
                                }
                            }
                        }
                    }
                    itemsIndexed(state.results, key = { index, _ -> "result-$index" }) { index, result ->
                        Surface(onClick = { onSelect(result) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-result-$index"),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(result.chapter, style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(buildAnnotatedString {
                                    append(result.before)
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(result.highlight) }
                                    append(result.after)
                                }, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun searchStatusText(state: BookSearchState): String = when (state.status) {
    BookSearchStatus.EMPTY -> "Enter words or a phrase to search this book."
    BookSearchStatus.SEARCHING -> "Searching this book…"
    BookSearchStatus.NO_RESULTS -> "No matches found. Try different words."
    BookSearchStatus.FAILED -> state.error ?: "This book could not be searched. Try again."
    BookSearchStatus.RESULTS -> when {
        state.capped -> "Showing the first 200 matches. Add more words to narrow your search."
        state.results.size == 1 -> "1 match"
        else -> "${state.results.size} matches"
    }
}
