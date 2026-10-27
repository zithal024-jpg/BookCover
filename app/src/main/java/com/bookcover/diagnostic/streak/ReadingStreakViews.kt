package com.bookcover.diagnostic.streak

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookcover.diagnostic.library.ChevronGlyph
import com.bookcover.diagnostic.library.homePanelColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Library card: streak, today's progress and the last seven days. Tapping opens the detail sheet. */
@Composable
internal fun ReadingStreakCard(records: ReadingDays, onCelebrated: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val today = rememberToday()
    val summary = StreakSummary.of(records, today)
    var details by remember { mutableStateOf(false) }
    // Once per qualifying day: the ribbon and today's mark fill in when the reader returns here.
    val celebrate = summary.todayComplete && records.celebrated != today
    val reduceMotion = rememberReducedMotion()
    val celebration = remember { Animatable(0f) }
    LaunchedEffect(celebrate) {
        if (!celebrate) return@LaunchedEffect
        if (reduceMotion) celebration.snapTo(1f)
        else { delay(250); celebration.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        onCelebrated(today)
    }
    val settle = if (celebrate) celebration.value else 1f
    val scheme = MaterialTheme.colorScheme
    // A compact summary row: the books lead on Library Home, the details sheet holds the rest.
    Surface(shape = RoundedCornerShape(16.dp), color = homePanelColor(),
        modifier = modifier.fillMaxWidth().testTag("reading-streak")
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open reading streak details") { details = true }) {
        BoxWithConstraints(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Side by side when the heading keeps a comfortable width; stacked on narrow screens or large text.
            val fontScale = LocalDensity.current.fontScale
            if (maxWidth - WEEK_STRIP_WIDTH - 24.dp >= 150.dp * fontScale) Row(verticalAlignment = Alignment.CenterVertically) {
                StreakHeading(summary, settle, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                WeekStrip(records, summary, settle)
                Spacer(Modifier.width(8.dp))
                ChevronGlyph(color = scheme.onSurfaceVariant)
            } else Column {
                StreakHeading(summary, settle)
                Spacer(Modifier.height(12.dp))
                WeekStrip(records, summary, settle, fill = true)
            }
        }
    }
    if (details) ReadingStreakSheet(records, summary) { details = false }
}

private val WEEK_STRIP_WIDTH = 22.dp * 7

internal fun streakTitle(summary: StreakSummary): String = when (summary.state) {
    StreakState.NEW_READER -> "Start your reading streak"
    StreakState.FRESH_START -> "Welcome back"
    else -> "${summary.current}-day streak"
}

internal fun streakStatus(summary: StreakSummary): String = when {
    summary.todayComplete -> "Today's goal complete"
    summary.todayMillis > 0 -> "${summary.todayMinutes} of $DAILY_GOAL_MINUTES minutes today"
    summary.state == StreakState.NEW_READER -> "Read $DAILY_GOAL_MINUTES minutes in a day to count it"
    summary.state == StreakState.FRESH_START -> "Read $DAILY_GOAL_MINUTES minutes today for a fresh start"
    else -> "0 of $DAILY_GOAL_MINUTES minutes today"
}

@Composable
private fun StreakHeading(summary: StreakSummary, settle: Float, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Ribbon(filled = if (summary.todayComplete) settle else 0f, Modifier.size(width = 14.dp, height = 20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(streakTitle(summary), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text(streakStatus(summary), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(if (summary.todayComplete) 0.35f + 0.65f * settle else 1f))
        }
    }
}

/** A bookmark ribbon: Book Cover's mark for a day of reading. Outlined until today's goal is met. */
@Composable
private fun Ribbon(filled: Float, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = 1.5.dp.toPx()
        // Inset by half the stroke so the outline is not clipped at the canvas edge.
        val left = stroke / 2; val right = size.width - stroke / 2
        val top = stroke / 2; val bottom = size.height - stroke / 2
        val path = Path().apply {
            moveTo(left, top); lineTo(right, top); lineTo(right, bottom)
            lineTo(size.width / 2, bottom - size.height * 0.28f); lineTo(left, bottom); close()
        }
        drawPath(path, color, style = Stroke(stroke, join = StrokeJoin.Round))
        if (filled > 0f) drawPath(path, color.copy(alpha = filled))
    }
}

/** The last seven days, today last. Shape tells the states apart, not colour alone. */
@Composable
private fun WeekStrip(records: ReadingDays, summary: StreakSummary, settle: Float, fill: Boolean = false) {
    val locale = currentLocale()
    Row(if (fill) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = if (fill) Arrangement.SpaceBetween else Arrangement.Start) {
        for (offset in 6 downTo 0) {
            val date = summary.today.minusDays(offset.toLong())
            val day = records.day(date)
            val isToday = offset == 0
            val label = dayLabel(date, day, summary, locale)
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(22.dp).semantics(mergeDescendants = true) { contentDescription = label }) {
                DayMark(complete = day.completed, isToday = isToday,
                    fraction = if (isToday) summary.todayFraction else 0f, settle = if (isToday) settle else 1f, size = 16.dp)
                Spacer(Modifier.height(2.dp))
                Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                    color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

internal fun dayLabel(date: LocalDate, day: ReadingDay, summary: StreakSummary, locale: Locale = Locale.getDefault()): String {
    val name = if (date == summary.today) "Today" else date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    return when {
        day.completed -> "$name, reading goal completed"
        date == summary.today -> "$name, ${summary.todayMinutes} of $DAILY_GOAL_MINUTES minutes read"
        day.millis >= 60_000L -> "$name, ${day.millis / 60_000L} minutes read"
        else -> "$name, no reading recorded"
    }
}

/** Completed: filled disc with a check. Today, open: a ring that fills with progress. Other days: a small dot. */
@Composable
private fun DayMark(complete: Boolean, isToday: Boolean, fraction: Float, settle: Float, size: Dp = 20.dp) {
    val scheme = MaterialTheme.colorScheme
    val pulse = if (settle < 1f) 1f + 0.16f * sin(PI * settle).toFloat() else 1f
    Canvas(Modifier.size(size).scale(pulse)) {
        val radius = this.size.minDimension / 2
        val ring = 2.dp.toPx()
        when {
            complete -> {
                if (settle < 1f) drawCircle(scheme.outlineVariant, radius - ring / 2, style = Stroke(ring))
                drawCircle(scheme.primary, radius * (0.35f + 0.65f * settle))
                if (settle > 0.45f) {
                    val c = center
                    val check = Path().apply {
                        moveTo(c.x - radius * 0.42f, c.y + radius * 0.02f)
                        lineTo(c.x - radius * 0.1f, c.y + radius * 0.34f)
                        lineTo(c.x + radius * 0.45f, c.y - radius * 0.3f)
                    }
                    drawPath(check, scheme.onPrimary.copy(alpha = ((settle - 0.45f) / 0.55f).coerceIn(0f, 1f)),
                        style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            isToday -> {
                drawCircle(scheme.outlineVariant, radius - ring / 2, style = Stroke(ring))
                if (fraction > 0f) drawArc(scheme.primary, -90f, 360f * fraction, useCenter = false,
                    topLeft = Offset(ring / 2, ring / 2),
                    size = androidx.compose.ui.geometry.Size(this.size.width - ring, this.size.height - ring),
                    style = Stroke(ring, cap = StrokeCap.Round))
            }
            else -> drawCircle(scheme.outline, 2.5.dp.toPx())
        }
    }
}

/** Detail sheet: the two streak numbers, today's progress, a month calendar and what counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingStreakSheet(records: ReadingDays, summary: StreakSummary, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().testTag("reading-streak-details").verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reading streak", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("reading-streak-close")) { Text("Done") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                StreakStat("Current streak", summary.current, Modifier.weight(1f).fillMaxHeight())
                StreakStat("Longest streak", summary.longest, Modifier.weight(1f).fillMaxHeight())
            }
            TodayProgress(summary)
            MonthCalendar(records, summary.today, Modifier.align(Alignment.CenterHorizontally))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Read for $DAILY_GOAL_MINUTES minutes in a day to build your streak.",
                    style = MaterialTheme.typography.titleSmall)
                Text("Time adds up across all your books and sessions while a book is open on screen. " +
                    "Each day that reaches $DAILY_GOAL_MINUTES minutes is marked on the calendar, and marked days in a row " +
                    "make your streak. After a day without reading, the next reading day starts a fresh streak; " +
                    "your longest streak is always kept.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Days follow your phone's calendar. Reading time stays on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StreakStat(label: String, days: Int, modifier: Modifier) {
    val value = if (days == 1) "1 day" else "$days days"
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) { }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun TodayProgress(summary: StreakSummary) {
    val text = if (summary.todayComplete) "Goal complete · ${summary.todayMinutes} minutes read"
        else "${summary.todayMinutes} of $DAILY_GOAL_MINUTES minutes"
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { summary.todayFraction }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
    }
}

/** One month at a time, starting on the locale's first day of the week; back to the first recorded month. */
@Composable
private fun MonthCalendar(records: ReadingDays, today: LocalDate, modifier: Modifier = Modifier) {
    val current = YearMonth.from(today)
    val earliest = records.days.keys.minOrNull()?.let(YearMonth::from)?.takeIf { it < current } ?: current
    var month by remember(current) { mutableStateOf(current) }
    val locale = currentLocale()
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val scheme = MaterialTheme.colorScheme
    val describe = remember(locale) { DateTimeFormatter.ofPattern("EEEE, MMMM d", locale) }
    Column(modifier.widthIn(max = 440.dp).fillMaxWidth().testTag("reading-streak-calendar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthStep(forward = false, enabled = month > earliest, label = "Previous month") { month = month.minusMonths(1) }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)), style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
            MonthStep(forward = true, enabled = month < current, label = "Next month") { month = month.plusMonths(1) }
        }
        Row(Modifier.clearAndSetSemantics { }) {
            for (i in 0 until 7) Text(firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        }
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val cells = lead + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) Row {
            for (column in 0 until 7) {
                val dayNumber = week * 7 + column - lead + 1
                Box(Modifier.weight(1f).padding(2.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (dayNumber in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayNumber)
                        val completed = records.completed(date)
                        val isToday = date == today
                        val state = when { completed -> "reading goal completed"; date > today -> "upcoming"
                            records.day(date).millis >= 60_000L -> "${records.day(date).millis / 60_000L} minutes read"
                            else -> "no reading recorded" }
                        Box(Modifier.fillMaxSize().clip(CircleShape)
                            .then(if (completed) Modifier.background(scheme.primary) else Modifier)
                            .then(if (isToday && !completed) Modifier.border(2.dp, scheme.primary, CircleShape) else Modifier)
                            .semantics { contentDescription = (if (isToday) "Today, " else "") + date.format(describe) + ", " + state },
                            contentAlignment = Alignment.Center) {
                            Text("$dayNumber", style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (completed || isToday) FontWeight.SemiBold else FontWeight.Normal),
                                color = when { completed -> scheme.onPrimary; date > today -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else -> scheme.onSurface })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthStep(forward: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(16.dp)) {
            val x = if (forward) size.width * 0.35f else size.width * 0.65f
            val tip = if (forward) size.width * 0.7f else size.width * 0.3f
            drawPath(Path().apply { moveTo(x, size.height * 0.15f); lineTo(tip, size.height / 2); lineTo(x, size.height * 0.85f) },
                color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Today's local date; refreshed each minute so the card rolls over at midnight while it is on screen. */
@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    return today
}

/** True when the phone's "Remove animations" setting turns animations off. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}


/** The app's current locale, read so that Compose recomposes when it changes. */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
