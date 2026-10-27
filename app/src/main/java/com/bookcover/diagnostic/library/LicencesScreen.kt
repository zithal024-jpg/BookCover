package com.bookcover.diagnostic.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About → Licences (BC-12): every bundled third-party licence, each with its full text on demand.
 * Full screen over Library settings; Back (button or system gesture) returns to settings.
 */
@Composable
internal fun LicencesScreen(onBack: () -> Unit, backDescription: String = "Back to Library settings") {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("licences-screen"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("licences-back")
                        .semantics { contentDescription = backDescription }) { Text("‹ Back") }
                    Text("Licences", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp).semantics { heading() })
                }
                LazyColumn(Modifier.fillMaxSize().testTag("licences-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("BookCover includes these open-source components. Each is used under the licence shown; " +
                            "open a licence to read its full text.", style = MaterialTheme.typography.bodyMedium)
                    }
                    itemsIndexed(BUNDLED_LICENCES) { index, notice -> LicenceCard(notice, index) }
                }
            }
        }
    }
}

@Composable
private fun LicenceCard(notice: LicenceNotice, index: Int) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val text by produceState<String?>(null, expanded) {
        if (expanded && value == null) value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(notice.textAsset).bufferedReader().use { it.readText() } }
                .getOrDefault("The licence text could not be read from the app.")
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(notice.component, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Licence: ${notice.licence}", style = MaterialTheme.typography.labelLarge)
            Text(notice.usedFor, style = MaterialTheme.typography.bodyMedium)
            Text(notice.credit, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("licence-toggle-$index")
                .semantics {
                    contentDescription = (if (expanded) "Hide" else "Show") + " full licence for ${notice.component}"
                    stateDescription = if (expanded) "Shown" else "Hidden"
                }) { Text(if (expanded) "Hide full licence" else "Show full licence") }
            if (expanded) Text(text ?: "Loading…", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("licence-text-$index"))
        }
    }
}
