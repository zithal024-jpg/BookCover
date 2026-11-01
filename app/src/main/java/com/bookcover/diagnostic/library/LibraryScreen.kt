package com.bookcover.diagnostic.library

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.cover.matchingCoverColor
import com.bookcover.diagnostic.streak.ReadingStreakCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Library Home's small design vocabulary: one spacing scale, three shapes, a serif for book titles.
 * Structural surfaces stay neutral so covers of any colour sit well together.
 */
private object Home {
    val CoverShape = RoundedCornerShape(4.dp)
    val PanelShape = RoundedCornerShape(16.dp)
    val Gap = 8.dp
    val Block = 16.dp
    val Section = 24.dp
    /** At or above this content width the screen is the unfolded one: toolbars join, the grid widens. */
    val Wide = 600.dp
    val Serif = FontFamily.Serif
}

private val SORTS = listOf("Recent", "Title", "Author")

/**
 * Panels sit one step off the background. Dark themes need a larger step to read as a surface,
 * so they use the next container tone rather than a mirror of the light value.
 */
@Composable
@ReadOnlyComposable
internal fun homePanelColor(): Color = MaterialTheme.colorScheme.let { if (it.background.luminance() < 0.5f) it.surfaceContainerHigh else it.surfaceContainer }

@Composable
@ReadOnlyComposable
private fun homeFieldColor(): Color = MaterialTheme.colorScheme.let { if (it.background.luminance() < 0.5f) it.surfaceContainerHighest else it.surfaceContainerHigh }

@Composable
internal fun LibraryScreen(
    books: List<BookRecord>, library: BookLibrary, busy: Boolean, message: String,
    autoCover: Boolean, timer: Int, onAutoCover: (Boolean) -> Unit, onTimer: (Int) -> Unit,
    onImport: () -> Unit, onOpen: (BookRecord) -> Unit, onMove: (BookRecord, String) -> Unit,
    onRemove: (BookRecord) -> Unit, onExportBackup: () -> Unit, onRestoreBackup: () -> Unit, onDiagnostics: () -> Unit,
    libraryTheme: String, onLibraryTheme: (String) -> Unit,
    importProgress: ImportProgress? = null, onStopImport: () -> Unit = {},
    coverColors: List<BookCoverColor> = emptyList(),
    onOpenRelics: () -> Unit = {},
    relicSummary: String = "",
) {
    var query by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("Recent") }
    var settings by remember { mutableStateOf(false) }
    // In memory only: diagnostics hide again when the app restarts.
    var developerOptions by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<BookRecord?>(null) }
    var removing by remember { mutableStateOf<BookRecord?>(null) }
    var collectionName by remember { mutableStateOf("") }
    // The book whose cover was tapped, so its cover can show that it is opening.
    var opening by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busy) { if (!busy) opening = null }
    var dismissedMessage by remember { mutableStateOf("") }
    val readingDays by library.readingDays.days.collectAsState()
    val streakScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try { library.readingDays.load() }
            catch (error: Exception) { DiagnosticLog.record("Reading days unavailable: ${error.javaClass.simpleName}") }
        }
    }
    val collections = remember(books) { listOf("All") + books.map { it.collection }.distinct().sorted() }
    val visible = remember(books, collection, query, sort) {
        books.filter { (collection == "All" || it.collection == collection) &&
            (it.title.contains(query, true) || it.author.contains(query, true)) }.let {
            when (sort) { "Title" -> it.sortedBy { b -> b.title.lowercase() }; "Author" -> it.sortedBy { b -> b.author.lowercase() }
                else -> it.sortedByDescending { b -> maxOf(b.lastReadAt, b.addedAt) } }
        }
    }
    val recent = remember(books) { books.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt } }
    val searching = query.isNotBlank()
    val open = { book: BookRecord -> if (!busy) { opening = book.id; onOpen(book) } }

    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val roomy = maxWidth >= Home.Wide
            // Large text needs the narrow arrangement even on the wide screen, so controls wrap instead of shrinking.
            val joined = roomy && LocalDensity.current.fontScale < 1.5f
            val gutter = if (roomy) Home.Section else Home.Block
            // One scrolling surface keeps the book grid reachable even when large system text makes
            // the header taller than the window. Nested fixed headers could leave a zero-height grid.
            LazyVerticalGrid(columns = GridCells.Adaptive(if (roomy) 136.dp else 140.dp),
                modifier = Modifier.testTag("library-scroll").fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Home.Block), verticalArrangement = Arrangement.spacedBy(Home.Section),
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Home.Section)) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(Home.Block)) {
                        HomeHeader(joined, books.isNotEmpty(), busy, query, { query = it }, onImport) { settings = true }
                        if (!joined && books.isNotEmpty()) SearchField(query, { query = it }, Modifier.fillMaxWidth())
                        if (importProgress != null) ImportProgressRow(importProgress, onStopImport)
                        if (message.isNotBlank() && message != dismissedMessage) StatusNote(message) { dismissedMessage = message }
                        AnimatedVisibility(visible = recent != null && !searching) {
                            recent?.let { ContinueCard(it, library, busy, opening == it.id && busy, roomy) { open(it) } }
                        }
                        // An empty library leads with how to add books; the streak follows.
                        if (books.isEmpty() && !busy) EmptyLibrary(onImport)
                        // Hidden while searching: the books stay the main thing here.
                        if (!searching) Column(horizontalAlignment = Alignment.End) {
                            ReadingStreakCard(readingDays,
                                onCelebrated = { date -> streakScope.launch(Dispatchers.IO) { runCatching { library.readingDays.markCelebrated(date) } } })
                            TextButton(onClick = onOpenRelics, enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("library-relic-cabinet")) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Relic cabinet")
                                    if (relicSummary.isNotBlank()) Text(relicSummary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (books.isNotEmpty()) CollectionToolbar(visible.size, books.size, sort, { sort = it }, collections, collection) { collection = it }
                        if (books.isNotEmpty() && visible.isEmpty()) NoMatches(searching) { query = ""; collection = "All" }
                    }
                }
                items(visible, key = { it.id }, contentType = { "book" }) { book ->
                    BookTile(book, library, busy, opening == book.id && busy, showCollection = collection == "All" && collections.size > 2,
                        onOpen = { open(book) }, onMove = { moving = book; collectionName = book.collection }, onRemove = { removing = book })
                }
            }
            // Overlaid, so ordinary library work never pushes the books down.
            if (busy && importProgress == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
        }
    }
    moving?.let { book -> AlertDialog(onDismissRequest = { moving = null }, title = { Text("Collection") },
        text = { OutlinedTextField(collectionName, { collectionName = it }, label = { Text("New or existing collection") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onMove(book, collectionName); collection = "All"; moving = null }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } }) }
    removing?.let { book -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${book.title}?") },
        text = { Text("Removes this app's copy, cover, bookmarks and progress. Your original file remains in Files.") },
        confirmButton = { TextButton(onClick = { onRemove(book); collection = "All"; removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
    if (settings) LibrarySettingsDialog(busy = busy, autoCover = autoCover, timer = timer, onAutoCover = onAutoCover,
        onTimer = onTimer, libraryTheme = libraryTheme, onLibraryTheme = onLibraryTheme,
        onExportBackup = onExportBackup, onRestoreBackup = onRestoreBackup, onDiagnostics = onDiagnostics,
        developerOptions = developerOptions, onDeveloperOptions = { developerOptions = true }, onDismiss = { settings = false },
        coverColors = coverColors, onOpenRelics = onOpenRelics)
}

/** Wordmark and the two app-level actions; on the unfolded screen search joins this row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeHeader(joined: Boolean, hasBooks: Boolean, busy: Boolean, query: String, onQuery: (String) -> Unit,
                       onImport: () -> Unit, onSettings: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = Home.Gap), horizontalArrangement = Arrangement.spacedBy(Home.Gap),
        verticalArrangement = Arrangement.spacedBy(Home.Gap), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("BookCover", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Home.Serif),
            modifier = Modifier.padding(end = Home.Block).semantics { heading() })
        if (joined && hasBooks) SearchField(query, onQuery, Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Home.Gap)) {
            FilledTonalButton(onClick = onImport, enabled = !busy, contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Import books" }) {
                PlusGlyph(); Spacer(Modifier.width(Home.Gap)); Text("Import")
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }) { GearGlyph() }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    TextField(value = query, onValueChange = onQuery, singleLine = true, shape = CircleShape,
        placeholder = { Text("Search titles and authors", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { SearchGlyph(color = scheme.onSurfaceVariant) },
        trailingIcon = if (query.isEmpty()) null else { {
            IconButton(onClick = { onQuery("") }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { CloseGlyph() }
        } },
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(focusedContainerColor = homeFieldColor(), unfocusedContainerColor = homeFieldColor(),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            unfocusedPlaceholderColor = scheme.onSurfaceVariant, focusedPlaceholderColor = scheme.onSurfaceVariant),
        modifier = modifier.semantics { contentDescription = "Search your library" })
}

/** A quiet note for the latest status message; the reader can dismiss it. */
@Composable
private fun StatusNote(message: String, onDismiss: () -> Unit) {
    Surface(shape = Home.PanelShape, color = homePanelColor(), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Home.Block)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).semantics { contentDescription = "Dismiss message" }) { CloseGlyph(16.dp) }
        }
    }
}

/** The hero: the most recently read book, where the reader left it, and one clear way back in. */
@Composable
private fun ContinueCard(book: BookRecord, library: BookLibrary, busy: Boolean, opening: Boolean, roomy: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val percent = (book.progression * 100).toInt()
    val chapter = remember(book.locator) { chapterTitle(book.locator) }
    val saved = savedLine(book)
    val progressLine = if (chapter != null) "$percent% read · $chapter" else "$percent% read"
    Surface(shape = Home.PanelShape, color = homePanelColor(),
        modifier = Modifier.fillMaxWidth().clip(Home.PanelShape).testTag("continue-reading")
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Resume reading") { onOpen() }
            .semantics { contentDescription = "Continue ${book.title}, $percent percent complete. $saved" }) {
        Row(Modifier.padding(Home.Block), horizontalArrangement = Arrangement.spacedBy(Home.Block)) {
            BookCover(book, library, opening, Modifier.width(if (roomy) 112.dp else 84.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = (if (roomy) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge).copy(fontFamily = Home.Serif))
                Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { book.progression.toFloat() }, drawStopIndicator = {},
                    trackColor = scheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().then(if (roomy) Modifier.widthIn(max = 420.dp) else Modifier).height(4.dp))
                Spacer(Modifier.height(Home.Gap))
                val progressText: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
                        Text(progressLine, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(saved, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                // Drawn, not a second button: the whole card is the one action, so TalkBack stops once.
                val resume: @Composable () -> Unit = {
                    Box(Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (busy) scheme.surfaceContainerHighest else scheme.primary)
                        .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text("Resume", style = MaterialTheme.typography.labelLarge, color = if (busy) scheme.onSurfaceVariant else scheme.onPrimary)
                    }
                }
                // Large text stacks the pill under the text rather than squeezing the saved-place line.
                if (LocalDensity.current.fontScale < 1.3f) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) { progressText(Modifier.weight(1f)); resume() }
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { progressText(Modifier); resume() }
            }
        }
    }
}

/** The saved-position reassurance under Continue. */
@Composable
private fun savedLine(book: BookRecord): String {
    val now = remember(book.lastReadAt) { System.currentTimeMillis() }
    val ago = DateUtils.getRelativeTimeSpanString(book.lastReadAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    return if (book.locator != null) "Place saved $ago" else "Opened $ago"
}

/** The chapter title Readium stored with the saved position, when it has one. */
internal fun chapterTitle(locator: String?): String? =
    locator?.let { runCatching { JSONObject(it).optString("title") }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

/** The count and sort on one line (wrapping with large text), then the collections, which scroll sideways. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionToolbar(shown: Int, total: Int, sort: String, onSort: (String) -> Unit,
                              collections: List<String>, collection: String, onCollection: (String) -> Unit) {
    val count = when { shown == total -> if (total == 1) "1 book" else "$total books"; else -> "$shown of $total books" }
    Column(Modifier.padding(top = Home.Gap)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Home.Gap), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(count, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Home.Serif),
                modifier = Modifier.padding(end = Home.Gap).semantics { heading() })
            SortControl(sort, onSort)
        }
        LazyRow(Modifier.fillMaxWidth().padding(top = Home.Gap), horizontalArrangement = Arrangement.spacedBy(Home.Gap)) {
            items(collections, key = { it }) { name ->
                FilterChip(selected = collection == name, onClick = { onCollection(name) }, label = { Text(name) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Collection: $name" })
            }
        }
    }
}

/** Selected state shows as fill and is announced; no check mark, so the control fits beside the count. */
@Composable
private fun SortControl(sort: String, onSort: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SORTS.forEachIndexed { index, name ->
            SegmentedButton(selected = sort == name, onClick = { onSort(name) }, shape = SegmentedButtonDefaults.itemShape(index, SORTS.size),
                modifier = Modifier.semantics { contentDescription = "Sort by $name" }, icon = {},
                label = { Text(name, maxLines = 1) })
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = Home.Block), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Home.Serif),
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text("Import a DRM-free EPUB or PDF from your phone's files to start reading. BookCover keeps its own copy; the original stays in Files.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Button(onClick = onImport, modifier = Modifier.padding(top = Home.Gap).heightIn(min = 48.dp)) {
            PlusGlyph(); Spacer(Modifier.width(Home.Gap)); Text("Import books")
        }
    }
}

@Composable
private fun NoMatches(searching: Boolean, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Home.Section), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (searching) "No books match your search" else "No books in this collection",
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (searching) "Clear search" else "Show all books") }
    }
}

@Composable
private fun BookTile(book: BookRecord, library: BookLibrary, busy: Boolean, opening: Boolean, showCollection: Boolean,
                     onOpen: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column {
        BookCover(book, library, opening, Modifier.fillMaxWidth().clip(Home.CoverShape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Open book") { onOpen() }
            .semantics { contentDescription = "Open ${book.title}, by ${book.author}" })
        // A fixed slot keeps rows aligned whether or not a book has been started.
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp)) {
            if (book.progression > 0.0) LinearProgressIndicator(progress = { book.progression.toFloat() }, drawStopIndicator = {},
                gapSize = 0.dp, trackColor = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxSize().clearAndSetSemantics { })
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = Home.Gap)) {
                Text(book.title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                Text(tileStatus(book, showCollection), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "Options for ${book.title}" }) { MoreGlyph(color = scheme.onSurfaceVariant) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Move to collection") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Remove local copy") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

internal fun tileStatus(book: BookRecord, showCollection: Boolean): String {
    val percent = (book.progression * 100).toInt()
    val progress = when { book.progression >= 0.995 -> "Finished"; percent > 0 -> "$percent%"; book.progression > 0.0 -> "Started"; else -> "New" }
    return if (showCollection) "$progress · ${book.collection}" else progress
}

/** A cover image and the colour of its edges, which fills around covers that are not 2:3. */
private class CoverArt(val bitmap: Bitmap, val edge: Color)

/**
 * The shared cover treatment: 2:3 frame, the artwork whole (never cropped), unusual shapes filled with
 * their own edge colour, a hairline edge and a quiet spinner while that book opens.
 */
@Composable
private fun BookCover(book: BookRecord, library: BookLibrary, opening: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val art by produceState<CoverArt?>(null, book.id, book.coverRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching { library.thumbnailBitmap(book).let { CoverArt(it, Color(edgeColor(it))) } }.getOrNull()
        }
    }
    Box(modifier.aspectRatio(2f / 3f).clip(Home.CoverShape).background(art?.edge ?: scheme.surfaceContainerHigh)
        .border(Dp.Hairline, scheme.onSurface.copy(alpha = 0.12f), Home.CoverShape), contentAlignment = Alignment.Center) {
        art?.let { Image(it.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (opening) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }
}

/** The dominant colour along a cover's four edges. Blocking; call off the main thread. */
private fun edgeColor(bitmap: Bitmap): Int {
    val w = bitmap.width; val h = bitmap.height
    if (w < 2 || h < 2) return 0xFF121212.toInt()
    val samples = ArrayList<Int>(128)
    for (i in 0 until 32) {
        val x = i * (w - 1) / 31; val y = i * (h - 1) / 31
        samples += bitmap.getPixel(x, 0); samples += bitmap.getPixel(x, h - 1)
        samples += bitmap.getPixel(0, y); samples += bitmap.getPixel(w - 1, y)
    }
    return matchingCoverColor(samples.toIntArray())
}
