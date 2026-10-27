package com.bookcover.diagnostic.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bookcover.diagnostic.ui.SettingsSection
import com.bookcover.diagnostic.ui.SettingsSwitchRow

/** Taps on the version line that reveal "Device diagnostics" (BC-23: hidden from readers). */
internal const val DEVELOPER_TAPS = 7

/**
 * Library settings, grouped by what a reader is looking for (BC-23). Diagnostics stay hidden
 * until [developerOptions] is turned on by tapping the version line [DEVELOPER_TAPS] times.
 */
@Composable
internal fun LibrarySettingsDialog(
    busy: Boolean, autoCover: Boolean, timer: Int, onAutoCover: (Boolean) -> Unit, onTimer: (Int) -> Unit,
    libraryTheme: String, onLibraryTheme: (String) -> Unit,
    onExportBackup: () -> Unit, onRestoreBackup: () -> Unit, onDiagnostics: () -> Unit,
    developerOptions: Boolean, onDeveloperOptions: () -> Unit, onDismiss: () -> Unit,
) {
    var versionTaps by remember { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Library settings") }, text = {
        Column(Modifier.testTag("library-settings-scroll").heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsSection("Appearance", summary = "Automatic follows the cover of your most recently read book. " +
                "A selected theme stays in use until you choose Automatic again.") {
                Text("Library theme", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) { LibraryThemeChoice.entries.forEach { choice ->
                    if (choice == LibraryThemeChoice.ROSE) Text("Colours · follow your phone's light or dark mode",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = LibraryThemeChoice.fromId(libraryTheme) == choice,
                            onClick = { onLibraryTheme(choice.id) }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = LibraryThemeChoice.fromId(libraryTheme) == choice, onClick = null)
                        Text(choice.label, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        // Decorative swatch; the label already names the colour for screen readers.
                        choice.seed?.let { seed -> Box(Modifier.size(20.dp).clip(CircleShape).background(Color(seed))) }
                    }
                } }
            }
            SettingsSection("Outside cover", showDivider = true) {
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
            SettingsSection("Backup", showDivider = true, summary = "Saves reading positions, bookmarks and collections to a file you choose. " +
                "Book files are not included; keep your EPUBs. To restore after reinstalling, import your EPUBs first.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onDismiss(); onExportBackup() }, enabled = !busy) { Text("Save backup") }
                    TextButton(onClick = { onDismiss(); onRestoreBackup() }, enabled = !busy) { Text("Restore backup") }
                }
            }
            SettingsSection("About", showDivider = true) {
                Text("EPUB engine: Readium 3.4.0 · BSD 3-Clause\nOpenDyslexic font by Abelardo Gonzalez · CC BY 3.0, based on Bitstream Vera",
                    style = MaterialTheme.typography.bodySmall)
                Text("BookCover 0.4.0 prototype", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("library-settings-version").clickable(enabled = !developerOptions) {
                        versionTaps += 1
                        if (versionTaps >= DEVELOPER_TAPS) onDeveloperOptions()
                    })
                if (developerOptions) TextButton(onClick = { onDismiss(); onDiagnostics() }) { Text("Device diagnostics") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
