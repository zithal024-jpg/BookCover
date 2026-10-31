package com.bookcover.diagnostic.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bookcover.diagnostic.ui.SettingsSection
import com.bookcover.diagnostic.ui.SettingsSwitchRow

/** Taps on the version line that reveal "Device diagnostics" (BC-23: hidden from readers). */
internal const val DEVELOPER_TAPS = 7

private enum class LibrarySettingsPage(val title: String) {
    ROOT("Library settings"), APPEARANCE("Appearance"), COVER("Outside cover"), BACKUP("Backup"), ABOUT("About"),
}

/** Compact entry points; details stay on one focused level, with no nested settings dialogs. */
@Composable
internal fun LibrarySettingsDialog(
    busy: Boolean, autoCover: Boolean, timer: Int, onAutoCover: (Boolean) -> Unit, onTimer: (Int) -> Unit,
    libraryTheme: String, onLibraryTheme: (String) -> Unit,
    onExportBackup: () -> Unit, onRestoreBackup: () -> Unit, onDiagnostics: () -> Unit,
    developerOptions: Boolean, onDeveloperOptions: () -> Unit, onDismiss: () -> Unit,
    coverColors: List<BookCoverColor> = emptyList(),
) {
    var page by remember { mutableStateOf(LibrarySettingsPage.ROOT) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var showLicences by remember { mutableStateOf(false) }
    if (showLicences) {
        LicencesScreen(onBack = { showLicences = false }, backDescription = "Back to About")
        return
    }
    val back: () -> Unit = { if (page == LibrarySettingsPage.ROOT) onDismiss() else page = LibrarySettingsPage.ROOT }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false),
        title = { Text(page.title, modifier = Modifier.semantics { heading() }) },
        text = {
            // Use this dialog's dispatcher: the activity's dispatcher cannot consume a dialog Back gesture.
            val dispatcherOwner = checkNotNull(LocalView.current.findViewTreeOnBackPressedDispatcherOwner()
                ?: LocalOnBackPressedDispatcherOwner.current)
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dispatcherOwner) {
                BackHandler(onBack = back)
            }
            key(page) {
                Column(Modifier.testTag("library-settings-scroll").heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (page) {
                        LibrarySettingsPage.ROOT -> {
                            SettingsCategory("Appearance", libraryThemeLabel(libraryTheme), "appearance") { page = LibrarySettingsPage.APPEARANCE }
                            SettingsCategory("Outside cover", (if (autoCover) "Starts with a book" else "Start manually") +
                                " · " + (if (timer == 0) "Manual timer" else "$timer min"), "cover") { page = LibrarySettingsPage.COVER }
                            SettingsCategory("Backup", "Save or restore positions, bookmarks and collections", "backup") { page = LibrarySettingsPage.BACKUP }
                            SettingsCategory("About", "BookCover 0.4.0 prototype · Licences", "about") { page = LibrarySettingsPage.ABOUT }
                        }
                        LibrarySettingsPage.APPEARANCE -> {
                            Text("Automatic follows the cover of your most recently read book. " +
                                "A selected theme stays in use until you choose Automatic again.", style = MaterialTheme.typography.bodySmall)
                            Text("Library theme", style = MaterialTheme.typography.labelLarge)
                            Column(Modifier.selectableGroup()) {
                                LibraryThemeChoice.entries.forEach { choice ->
                                    if (choice == LibraryThemeChoice.ROSE) Text("Colours · follow your phone's light or dark mode",
                                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                                    ThemeRow(choice.id, choice.label, choice.seed, libraryTheme == choice.id, onLibraryTheme)
                                }
                            }
                            val derived = remember(coverColors, libraryTheme) { coverColorChoices(coverColors, libraryTheme) }
                            SettingsSection("From your covers", summary = "Colours from your books, following your phone's light or dark mode. Similar colours are grouped.") {
                                if (derived.isEmpty()) Text("Import a book with a cover to add its colour here.", style = MaterialTheme.typography.bodySmall)
                                Column(Modifier.selectableGroup()) {
                                    derived.forEach { choice -> ThemeRow(choice.id, choice.label, choice.seed, libraryTheme == choice.id, onLibraryTheme) }
                                }
                            }
                        }
                        LibrarySettingsPage.COVER -> {
                            SettingsSwitchRow("Start outside cover when opening a book", autoCover, onAutoCover)
                            Text("Outside cover timer", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(30, 60, 0).forEach { minutes -> FilterChip(timer == minutes, { onTimer(minutes) },
                                    label = { Text(if (minutes == 0) "Manual" else "$minutes min") }, modifier = Modifier.heightIn(min = 48.dp)
                                        .semantics { contentDescription = if (minutes == 0) "Outside cover timer: manual" else "Outside cover timer: $minutes minutes" }) }
                            }
                            Text("Keep BookCover in front while reading. Tap the middle of a page to show controls. Swipe left or right to turn pages.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        LibrarySettingsPage.BACKUP -> {
                            Text("Saves reading positions, bookmarks and collections to a file you choose. " +
                                "Book files are not included; keep your EPUBs and PDFs. To restore after reinstalling, import your books first.",
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { onDismiss(); onExportBackup() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save backup") }
                                TextButton(onClick = { onDismiss(); onRestoreBackup() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore backup") }
                            }
                        }
                        LibrarySettingsPage.ABOUT -> {
                            Text("Reading engine: Readium 3.4.0 · BSD 3-Clause\nOpenDyslexic font by Abelardo Gonzalez · CC BY 3.0, based on Bitstream Vera",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { showLicences = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("library-settings-licences")) { Text("Licences") }
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-settings-version")
                                .clickable(enabled = !developerOptions) {
                                    versionTaps += 1
                                    if (versionTaps >= DEVELOPER_TAPS) onDeveloperOptions()
                                }, contentAlignment = Alignment.CenterStart) {
                                Text("BookCover 0.4.0 prototype", style = MaterialTheme.typography.bodySmall)
                            }
                            if (developerOptions) TextButton(onClick = { onDismiss(); onDiagnostics() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Device diagnostics") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } },
        dismissButton = {
            if (page != LibrarySettingsPage.ROOT) TextButton(onClick = back,
                modifier = Modifier.heightIn(min = 48.dp).testTag("library-settings-back").semantics { contentDescription = "Back to Library settings" }) { Text("‹ Back") }
        },
    )
}

@Composable
private fun SettingsCategory(label: String, summary: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("library-settings-$tag")
        .clickable(role = Role.Button, onClickLabel = "Open $label", onClick = onClick)
        .semantics { contentDescription = label; stateDescription = summary }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun ThemeRow(id: String, label: String, seed: Int?, selected: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-theme-$id")
        .selectable(selected = selected, onClick = { onSelect(id) }, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp, end = 8.dp).weight(1f))
        seed?.let { Box(Modifier.size(20.dp).clip(CircleShape).background(Color(it))) }
    }
}
