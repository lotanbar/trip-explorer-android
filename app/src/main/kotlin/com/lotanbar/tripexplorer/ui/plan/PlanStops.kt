package com.lotanbar.tripexplorer.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.data.Plans
import com.lotanbar.tripexplorer.ui.theme.resolvedTextAlign
import com.lotanbar.tripexplorer.ui.theme.resolvedTextDirection
import java.io.File
import java.util.Locale

/**
 * One plan's stops, in the saved order, shown under the plan when it is expanded. A plan is a list
 * of places, not a route: no numbers. Tapping a stop opens Waze navigating to it. The box at the
 * row's end ticks the stop as visited (a thin line through its name), written into the plan file so
 * the PC sees it too.
 */
@Composable
fun PlanStops(file: File) {
    val context = LocalContext.current
    var stops by remember(file, file.lastModified()) { mutableStateOf(Plans.read(file)) }
    var message by remember { mutableStateOf<String?>(null) }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)) {
        if (stops.isEmpty()) {
            Text(
                "No stops in this plan",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        stops.forEachIndexed { index, stop ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (!Plans.openInWaze(context, stop)) message = "Waze is not installed." }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stop.name,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = stop.name.resolvedTextDirection(), textAlign = stop.name.resolvedTextAlign()),
                        fontWeight = FontWeight.Medium,
                        color = if (stop.visited) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (stop.visited) TextDecoration.LineThrough else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", stop.lat, stop.lon),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Checkbox(
                    checked = stop.visited,
                    onCheckedChange = { checked ->
                        if (Plans.setVisited(file, index, checked)) stops = stops.toMutableList().also { it[index] = stop.copy(visited = checked) }
                        else message = "Could not save the plan file."
                    },
                )
            }
        }
    }
}
