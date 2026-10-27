package com.bookcover.diagnostic.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bookcover.diagnostic.shortFailureReason

/** "Importing book 3 of 20…" with a bar that fills as books finish, and a way to stop (BC-27). */
@Composable
internal fun ImportProgressRow(progress: ImportProgress, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(importProgressMessage(progress), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("import-progress").semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop importing") }
    }
}

/** The end of a batch: how many were imported, which were already there, and which failed and why. */
@Composable
internal fun ImportSummaryDialog(summary: ImportSummary, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (summary.cancelled) "Import stopped" else "Import finished") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).testTag("import-summary"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(importSummaryMessage(summary))
                if (summary.failed.isNotEmpty()) {
                    Text("Couldn't be imported", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    summary.failed.forEach { Text("${it.name}: ${shortFailureReason(it.reason)}", style = MaterialTheme.typography.bodySmall) }
                }
                if (summary.duplicates.isNotEmpty()) {
                    Text("Already in your library", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    summary.duplicates.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}
