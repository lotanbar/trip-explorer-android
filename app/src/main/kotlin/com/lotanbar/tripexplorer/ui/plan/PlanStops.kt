package com.lotanbar.tripexplorer.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * One plan's stops, numbered, in the saved order, shown under the plan when it is expanded.
 * Tapping a stop opens Waze navigating to it; a Waze link holds one destination, so the stop
 * after the last one opened is highlighted as "next". The box at the row's end ticks the stop as
 * visited (a thin line through its name), written into the plan file so the PC sees it too.
 */
@Composable
fun PlanStops(file: File) {
    val context = LocalContext.current
    var stops by remember(file, file.lastModified()) { mutableStateOf(Plans.read(file)) }
    var lastOpened by remember(file) { mutableIntStateOf(Plans.lastOpened(context, file)) }
    var message by remember { mutableStateOf<String?>(null) }
    val next = if (lastOpened + 1 < stops.size) lastOpened + 1 else -1

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
            val isNext = index == next
            val done = index <= lastOpened
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (Plans.openInWaze(context, stop)) {
                            lastOpened = index
                            Plans.setLastOpened(context, file, index)
                        } else {
                            message = "Waze is not installed."
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isNext) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stop.name,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = stop.name.resolvedTextDirection(), textAlign = stop.name.resolvedTextAlign()),
                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
                        color = if ((done || stop.visited) && !isNext) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (stop.visited) TextDecoration.LineThrough else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        listOfNotNull(
                            String.format(Locale.US, "%.5f, %.5f", stop.lat, stop.lon),
                            if (isNext) "next" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
