package com.bookcover.diagnostic.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.fragment.app.FragmentContainerView
import com.bookcover.diagnostic.R
import com.bookcover.diagnostic.library.BookRecord
import kotlin.math.roundToInt

/** Page colour for a reading theme; also used for the plain screen shown while a book reopens. */
internal fun readerBackground(theme: String?): Color =
    when (theme) { "Night" -> Color.Black; "Sepia" -> Color(0xFFFAF4E8); else -> Color.White }

@Composable
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
    onBookmark: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    var contentsVisible by remember(session) { mutableStateOf(false) }
    val night = session.themeName == "Night"
    val background = readerBackground(session.themeName)
    val scheme = if (night) darkColorScheme() else lightColorScheme()
    BackHandler {
        when { contentsVisible -> contentsVisible = false; session.controlsVisible -> session.controlsVisible = false; else -> onHome() }
    }
    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(color = background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            FragmentContainerView(context).apply {
                                id = R.id.epub_reader_container
                                doOnAttach { post { session.attach(this) } }
                            }
                        },
                        update = { if (it.isAttachedToWindow) session.attach(it) },
                        modifier = Modifier.fillMaxSize().padding(bottom = 30.dp),
                    )
                    Text("${(session.progress * 100).toInt().coerceIn(0, 100)}%",
                        color = if (night) Color(0xFFBCBDB5) else Color(0xFF555E53),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 12.dp)
                            .semantics { contentDescription = "Book completion ${(session.progress * 100).toInt()} percent" })
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
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (contentsVisible) {
                            Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
                            if (record.bookmarks.isEmpty()) Text("No bookmarks yet.")
                            record.bookmarks.forEach { value ->
                                val locator = remember(value) { runCatching { org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(value)) }.getOrNull() }
                                if (locator != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { session.fragment.go(locator, animated = false); contentsVisible = false; session.controlsVisible = false }, modifier = Modifier.weight(1f)) {
                                        Text("${((locator.locations.totalProgression ?: 0.0) * 100).toInt()}% · ${locator.title ?: "Saved page"}")
                                    }
                                    TextButton(onClick = { onRemoveBookmark(value) }) { Text("Remove") }
                                }
                            }
                            Text("Contents", style = MaterialTheme.typography.titleLarge)
                            fun flatten(links: List<org.readium.r2.shared.publication.Link>, depth: Int = 0): List<Pair<org.readium.r2.shared.publication.Link, Int>> =
                                links.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }
                            val contents = flatten(session.publication.tableOfContents)
                            if (contents.isEmpty()) Text("This book does not provide a table of contents.")
                            contents.forEach { (link, depth) ->
                                TextButton(onClick = { session.fragment.go(link, animated = false); contentsVisible = false; session.controlsVisible = false },
                                    modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(4) * 12).dp)) {
                                    Text(link.title ?: "Chapter", modifier = Modifier.fillMaxWidth())
                                }
                            }
                            TextButton(onClick = { contentsVisible = false }) { Text("Back to controls") }
                        } else {
                            Text(record.title, style = MaterialTheme.typography.titleMedium)
                            Text(when { stopPending -> "Stopping outside cover…"; coverActive -> "Outside cover on"; coverBusy -> "Starting outside cover…"; else -> "Outside cover off" },
                                style = MaterialTheme.typography.labelLarge)
                            Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            if (coverBusy) OutlinedButton(onClick = onStopCover, enabled = !stopPending, modifier = Modifier.fillMaxWidth()) { Text("Stop outer cover") }
                            else OutlinedButton(onClick = onStartCover, enabled = canStartCover, modifier = Modifier.fillMaxWidth()) { Text("Start outer cover") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { contentsVisible = true }, enabled = !session.typographyUpdating) { Text("Contents") }
                                OutlinedButton(onClick = onBookmark, enabled = !session.typographyUpdating) { Text("Bookmark") }
                            }
                            Text("Typography", style = MaterialTheme.typography.titleMedium)
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
                                Text("${if (session.comfort.publisherStyles) "Saved text size" else "Text size"}: ${ReadingTextSize.percent(session.fontScale)}%", style = MaterialTheme.typography.labelLarge)
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ReadingComfort.lineSpacingChoices.forEach { spacing ->
                                    FilterChip(selected = session.comfort.lineSpacing == spacing,
                                        onClick = { session.changeLineSpacing(spacing) }, enabled = typeEnabled,
                                        label = { Text("${spacing}×") },
                                        modifier = Modifier.weight(1f).semantics { contentDescription = "Line spacing $spacing" })
                                }
                            }
                            Text("Side margins", style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ReadingMargins.entries.forEach { margin ->
                                    FilterChip(selected = session.comfort.margins == margin, onClick = { session.changePageMargins(margin.id) },
                                        enabled = typeEnabled, label = { Text(margin.label) }, modifier = Modifier.weight(1f))
                                }
                            }
                            Text("Alignment", style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ReadingAlignment.entries.forEach { alignment ->
                                    FilterChip(selected = session.comfort.alignment == alignment,
                                        onClick = { session.changeTextAlignment(alignment.id) }, enabled = typeEnabled,
                                        label = { Text(alignment.label) }, modifier = Modifier.weight(1f))
                                }
                            }
                            if (session.typographyUpdating) Text("Reflowing text and restoring your reading position…", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Edge-tap page turns", style = MaterialTheme.typography.labelLarge)
                                    Text("Optional narrow left/right edges. Swipes always work.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = session.edgeTaps, onCheckedChange = session::changeEdgeTaps,
                                    modifier = Modifier.semantics { contentDescription = "Enable edge-tap page turns" })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Paper", "Sepia", "Night").forEach { name ->
                                    OutlinedButton(onClick = { session.changeTheme(name) }, modifier = Modifier.weight(1f)) { Text(if (session.themeName == name) "✓ $name" else name) }
                                }
                            }
                            TextButton(onClick = onChooseCover) { Text("Choose cover image (stops outside cover)") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { session.previous() }, enabled = !session.typographyUpdating) { Text("Previous") }
                                TextButton(onClick = { session.next() }, enabled = !session.typographyUpdating) { Text("Next") }
                            }
                            Button(onClick = { session.controlsVisible = false }, modifier = Modifier.fillMaxWidth()) { Text("Resume reading") }
                        }
                    }
                }
            }
        }
    }
}
