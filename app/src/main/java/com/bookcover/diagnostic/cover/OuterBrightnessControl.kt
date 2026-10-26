package com.bookcover.diagnostic.cover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A public window-brightness request, not an Always On Display mode or power-savings claim. */
@Composable
internal fun OuterBrightnessControl(value: Float, onValueChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = if (value < 0f) "Phone setting" else "${(value * 100).roundToInt()}% requested"
        Text("Outer cover brightness: $label", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = if (value < 0f) 0.5f else value,
            onValueChange = onValueChange,
            valueRange = 0.1f..1f,
            steps = 8,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Outer cover brightness request" },
        )
        TextButton(onClick = { onValueChange(-1f) }, enabled = value >= 0f) { Text("Use phone setting") }
        Text("Check the outside screen. Your phone may ignore independent brightness requests. This is not Always On Display.",
            style = MaterialTheme.typography.bodySmall)
    }
}
