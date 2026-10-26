package com.bookcover.diagnostic.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.cover.OuterBrightnessControl
import kotlin.math.abs
import kotlin.math.roundToInt

/** A native, same-activity lifecycle experiment. This does not yet open EPUBs. */
@Composable
fun SampleReaderScreen(
    coverActive: Boolean,
    sessionMessage: String,
    canStartCover: Boolean,
    coverRequested: Boolean,
    stopPending: Boolean,
    outerBrightness: Float,
    onOuterBrightnessChange: (Float) -> Unit,
    onStartCover: () -> Unit,
    onStopCover: () -> Unit,
    onExit: () -> Unit,
) {
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var textSize by rememberSaveable { mutableIntStateOf(22) }
    var themeName by rememberSaveable { mutableStateOf("Paper") }
    // Controls are deliberately transient. Returning/recreating resumes the quiet page.
    var controlsVisible by remember { mutableStateOf(false) }
    val latestCoverActive by rememberUpdatedState(coverActive)
    val palette = when (themeName) {
        "Night" -> ReaderPalette(Color(0xFF20231F), Color(0xFFE8E6DC), Color(0xFFBAC4B6), Color(0xFF2C342A))
        "Sepia" -> ReaderPalette(Color(0xFFF1E2C4), Color(0xFF3C3022), Color(0xFF665540), Color(0xFFE5D3AE))
        else -> ReaderPalette(Color(0xFFFAF6EB), Color(0xFF27352C), Color(0xFF586653), Color(0xFFEEF0E6))
    }
    val scheme = if (themeName == "Night") {
        darkColorScheme(background = palette.paper, onBackground = palette.ink,
            surface = palette.toolbar, onSurface = palette.ink)
    } else {
        lightColorScheme(background = palette.paper, onBackground = palette.ink,
            surface = palette.toolbar, onSurface = palette.ink)
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val bodyStyle = TextStyle(
        color = palette.ink,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = textSize.sp,
        lineHeight = (textSize * 1.5f).sp,
        letterSpacing = 0.sp,
    )
    val setControls: (Boolean) -> Unit = { visible ->
        controlsVisible = visible
        DiagnosticLog.record("Reader controls ${if (visible) "revealed" else "hidden"}; same host activity")
    }
    BackHandler {
        if (controlsVisible) setControls(false) else onExit()
    }

    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(palette.paper).safeDrawingPadding(),
        ) {
            // Match the body's actual column padding and the small footer's measured line.
            val widthPx = with(density) { (maxWidth - 48.dp).roundToPx() }.coerceAtLeast(1)
            val heightPx = with(density) {
                (maxHeight - 72.dp).roundToPx() - 16.sp.roundToPx() - 2
            }.coerceAtLeast(1)
            val pages = remember(measurer, widthPx, heightPx, textSize, density.fontScale) {
                paginateSample(measurer, bodyStyle, widthPx, heightPx)
            }
            val pageIndex = pageForAnchor(pages, anchorOffset)
            val currentPage = pages[pageIndex]
            val currentText = SampleBook.text.substring(currentPage.start, currentPage.end)
            val progress = sampleProgressPercent(SampleBook.text.length, anchorOffset)
            val thresholdPx = with(density) { 48.dp.toPx() }
            val turnPage: (Int) -> Unit = { direction ->
                val destination = (pageIndex + direction).coerceIn(0, pages.lastIndex)
                if (destination != pageIndex) {
                    anchorOffset = pages[destination].start
                    DiagnosticLog.record("Reader sample page ${destination + 1}/${pages.size}; coverActive=$latestCoverActive; same host activity")
                }
                controlsVisible = false
            }
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyUp) false
                            else when (event.key) {
                                Key.DirectionRight -> { turnPage(1); true }
                                Key.DirectionLeft -> { turnPage(-1); true }
                                else -> false
                            }
                        }
                        .focusable()
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Show reading controls") { setControls(true); true },
                                CustomAccessibilityAction("Next sample page") { turnPage(1); true },
                                CustomAccessibilityAction("Previous sample page") { turnPage(-1); true },
                            )
                        }
                        .pointerInput(pageIndex, pages.size, textSize) {
                            detectTapGestures(onTap = { point ->
                                if (point.x in (size.width * 0.25f)..(size.width * 0.75f) &&
                                    point.y in (size.height * 0.12f)..(size.height * 0.88f)) {
                                    setControls(!controlsVisible)
                                }
                            })
                        }
                        .pointerInput(pageIndex, pages.size, textSize) {
                            // Lock the axis before consuming movement. A generic drag detector
                            // consumes vertical motion too, which would break the rare scroll fallback.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var horizontalLocked = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                                    val movement = change.position - down.position
                                    val horizontal = abs(movement.x)
                                    val vertical = abs(movement.y)
                                    if (!horizontalLocked) {
                                        // A gesture that begins vertically belongs to scrolling;
                                        // bending it horizontally later must not turn a page.
                                        if (vertical > viewConfiguration.touchSlop && vertical >= horizontal) break
                                        horizontalLocked = horizontal > viewConfiguration.touchSlop && horizontal > vertical * 1.35f
                                    }
                                    if (horizontalLocked) change.consume()
                                    if (!change.pressed) {
                                        if (horizontalLocked && horizontal >= thresholdPx && horizontal > vertical * 1.35f) {
                                            turnPage(if (movement.x < 0) 1 else -1)
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    val scroll = remember(currentPage, textSize) { androidx.compose.foundation.ScrollState(0) }
                    Text(
                        text = currentText,
                        style = bodyStyle,
                        modifier = Modifier.fillMaxWidth().then(
                            if (currentPage.needsVerticalScroll) Modifier.verticalScroll(scroll) else Modifier,
                        ),
                        overflow = TextOverflow.Visible,
                    )
                }
                Text(
                    "$progress%",
                    color = palette.secondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                        .semantics {
                            contentDescription = "Sample reading position: $progress percent"
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }

            if (controlsVisible) {
                Surface(
                    color = palette.toolbar,
                    tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onExit) { Text("Setup") }
                        Text("Sample reader", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { setControls(false) }) { Text("Read") }
                    }
                }
                Surface(
                    color = palette.toolbar,
                    tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                        .heightIn(max = maxHeight * 0.78f),
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(SampleBook.title, style = MaterialTheme.typography.titleMedium)
                        Text("Original sample text · page ${pageIndex + 1} of ${pages.size}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                stopPending -> "Stopping the outer-cover session…"
                                coverActive -> "Outer-cover session active. Confirm the physical outside screen."
                                coverRequested -> "Opening the outer-cover session…"
                                else -> "Outer-cover session off."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(sessionMessage, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (coverRequested) {
                            OutlinedButton(onClick = onStopCover, enabled = !stopPending, modifier = Modifier.fillMaxWidth()) {
                                Text(if (stopPending) "Stopping…" else "Stop outer cover")
                            }
                        } else {
                            OutlinedButton(onClick = onStartCover, enabled = canStartCover, modifier = Modifier.fillMaxWidth()) {
                                Text("Start outer cover")
                            }
                        }
                        Text("Text size: $textSize", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = textSize.toFloat(),
                            onValueChange = { textSize = it.roundToInt() },
                            onValueChangeFinished = { DiagnosticLog.record("Reader font size=$textSize; retained character anchor") },
                            valueRange = 18f..28f,
                            steps = 9,
                            modifier = Modifier.semantics { contentDescription = "Reading text size" },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Paper", "Sepia", "Night").forEach { name ->
                                OutlinedButton(
                                    onClick = { themeName = name; DiagnosticLog.record("Reader appearance=$name") },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (themeName == name) "✓ $name" else name) }
                            }
                        }
                        OuterBrightnessControl(outerBrightness, onOuterBrightnessChange)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { turnPage(-1) }, enabled = pageIndex > 0) { Text("Previous") }
                            Text("${pageIndex + 1} / ${pages.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { turnPage(1) }, enabled = pageIndex < pages.lastIndex) { Text("Next") }
                        }
                        Button(onClick = { setControls(false) }, modifier = Modifier.fillMaxWidth()) { Text("Resume reading") }
                    }
                }
            }
        }
    }
}

private data class ReaderPalette(val paper: Color, val ink: Color, val secondary: Color, val toolbar: Color)

/** Fill each page from the continuous publication, with its original paragraph breaks intact. */
private fun paginateSample(measurer: TextMeasurer, style: TextStyle, widthPx: Int, heightPx: Int): List<SamplePage> {
    return buildSamplePages(SampleBook.text) { remaining ->
        val layout = measurer.measure(remaining, style = style, constraints = Constraints(maxWidth = widthPx))
        var finalLine = -1
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) <= heightPx) finalLine = line else break
        }
        val needsScroll = finalLine < 0
        val count = when {
            finalLine == layout.lineCount - 1 -> remaining.length
            finalLine >= 0 -> layout.getLineEnd(finalLine, visibleEnd = false)
            else -> layout.getLineEnd(0, visibleEnd = false)
        }.coerceAtLeast(Character.charCount(remaining.codePointAt(0))).coerceAtMost(remaining.length)
        SamplePageFit(count, needsScroll)
    }
}
