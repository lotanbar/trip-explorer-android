package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.data.Groups
import com.lotanbar.tripexplorer.data.PoiStore
import com.lotanbar.tripexplorer.data.Trips
import com.lotanbar.tripexplorer.ui.theme.resolvedTextAlign
import com.lotanbar.tripexplorer.ui.theme.resolvedTextDirection
import java.io.File

/** All POIs of all trips, following the folders, grouped by trip. */
@Composable
fun PoiListScreen(onOpen: (File) -> Unit) {
    val context = LocalContext.current
    val current = remember { Trips.currentTrip(context) }
    val byTrip = remember {
        val pois = PoiStore.readAll().groupBy { it.trip }
        // Current trip first, then the rest alphabetically.
        pois.keys.sortedWith(compareBy<String> { it != current }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }).map { it to pois.getValue(it) }
    }
    if (byTrip.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No POIs yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        byTrip.forEach { (trip, pois) ->
            item(key = "trip:$trip") {
                Text(
                    trip,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = trip.resolvedTextDirection(), textAlign = trip.resolvedTextAlign()),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(pois, key = { it.dir.absolutePath }) { poi ->
                val group = Groups.byKey(poi.groupKey)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(poi.dir) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        painter = painterResource(group?.iconRes ?: Groups.noGroupIcon),
                        contentDescription = group?.name ?: "No group",
                        tint = group?.color ?: Groups.noGroupColor,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            poi.name,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = poi.name.resolvedTextDirection(), textAlign = poi.name.resolvedTextAlign()),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val photos = poi.media.count { !PoiStore.isAudio(it) }
                        val notes = poi.media.size - photos
                        Text(
                            listOfNotNull(
                                poi.datetime.ifBlank { null },
                                if (photos > 0) "$photos photo${if (photos > 1) "s" else ""}" else null,
                                if (notes > 0) "$notes note${if (notes > 1) "s" else ""}" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}
