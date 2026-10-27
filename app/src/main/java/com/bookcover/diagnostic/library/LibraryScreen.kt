package com.bookcover.diagnostic.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryScreen(
    books: List<BookRecord>, library: BookLibrary, busy: Boolean, message: String,
    autoCover: Boolean, timer: Int, onAutoCover: (Boolean) -> Unit, onTimer: (Int) -> Unit,
    onImport: () -> Unit, onOpen: (BookRecord) -> Unit, onMove: (BookRecord, String) -> Unit,
    onRemove: (BookRecord) -> Unit, onExportBackup: () -> Unit, onRestoreBackup: () -> Unit, onDiagnostics: () -> Unit,
    libraryTheme: String, onLibraryTheme: (String) -> Unit,
    importProgress: ImportProgress? = null, onStopImport: () -> Unit = {},
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
    val collections = listOf("All") + books.map { it.collection }.distinct().sorted()
    val visible = books.filter { (collection == "All" || it.collection == collection) &&
        (it.title.contains(query, true) || it.author.contains(query, true)) }.let {
        when(sort) { "Title" -> it.sortedBy { b -> b.title.lowercase() }; "Author" -> it.sortedBy { b -> b.author.lowercase() }
            else -> it.sortedByDescending { b -> maxOf(b.lastReadAt, b.addedAt) } }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        // One scrolling surface keeps the book grid reachable even when large system text makes
        // the header taller than the window. Nested fixed headers could leave a zero-height grid.
        LazyVerticalGrid(columns = GridCells.Adaptive(145.dp),
            modifier = Modifier.testTag("library-scroll").fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column {
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text("BookCover", style = MaterialTheme.typography.headlineLarge)
                    Text("Your books, inside and out", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { settings = true }) { Text("Settings") }
                Button(onClick = onImport, enabled = !busy) { Text("Import EPUB") }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search your library") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp), singleLine = true)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(collections.size) { index -> val name = collections[index]
                    FilterChip(selected = collection == name, onClick = { collection = name }, label = { Text(name) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Collection: $name" }) }
            }
            Text("${visible.size} books", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Recent", "Title", "Author").forEach { name ->
                    FilterChip(selected = sort == name, onClick = { sort = name }, label = { Text(name) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Sort by $name" })
                }
            }
            if (importProgress != null) ImportProgressRow(importProgress, onStopImport)
            else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            val recent = books.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt }
            if (recent != null && query.isBlank() && collection == "All") {
                FilledTonalButton(onClick = { onOpen(recent) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .semantics { contentDescription = "Continue ${recent.title}, ${(recent.progression * 100).toInt()} percent complete" }) {
                    Text("Continue · ${recent.title} · ${(recent.progression * 100).toInt()}%", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (visible.isEmpty() && !busy) Text("Import a DRM-free EPUB from your phone's files to start reading.", modifier = Modifier.padding(24.dp))
            } }
                items(visible, key = { it.id }) { book ->
                    var menu by remember { mutableStateOf(false) }
                    val cover by produceState<android.graphics.Bitmap?>(null, book.id, book.coverRevision) {
                        value = withContext(Dispatchers.IO) { library.thumbnailBitmap(book) }
                    }
                    Column {
                        Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.67f)
                                .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Open book") { onOpen(book) }
                                .semantics { contentDescription = "Open ${book.title}, by ${book.author}" }) {
                            cover?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text("${book.collection} · ${(book.progression * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(48.dp)
                                    .semantics { contentDescription = "Options for ${book.title}" }) {
                                    val iconColor = LocalContentColor.current
                                    Canvas(Modifier.size(24.dp)) {
                                        listOf(6f, 12f, 18f).forEach { y ->
                                            drawCircle(iconColor, radius = 2.dp.toPx(), center = Offset(size.width / 2, y.dp.toPx()))
                                        }
                                    }
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Move to collection") }, onClick = { menu = false; moving = book; collectionName = book.collection })
                                    DropdownMenuItem(text = { Text("Remove local copy") }, onClick = { menu = false; removing = book })
                                }
                            }
                        }
                    }
            }
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
        developerOptions = developerOptions, onDeveloperOptions = { developerOptions = true }, onDismiss = { settings = false })
}
