package com.lotanbar.tripexplorer.ui.poi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lotanbar.tripexplorer.data.Categories
import com.lotanbar.tripexplorer.data.Category
import com.lotanbar.tripexplorer.data.PoiEntry
import com.lotanbar.tripexplorer.data.PoiStore
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.US)

private fun PoiEntry.cat(): Category = Categories.byKey(category) ?: Categories.byKey(Categories.NONE)!!
private fun PoiEntry.whenText(): String = time?.let(WHEN::format) ?: dir.name

/** The current trip's POIs, newest first: category, date and time, photo count. A tap opens the POI screen. */
@Composable
fun PoiList(trip: String?, pois: List<PoiEntry>, onOpen: (PoiEntry) -> Unit, modifier: Modifier = Modifier) {
    if (trip == null || pois.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (trip == null) "No trip yet" else "No POIs in this trip yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier) {
        items(pois, key = { it.dir.path }) { poi ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(poi) }.padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(poi.cat(), 44)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(poi.cat().name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(poi.whenText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (poi.photos.isNotEmpty()) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Photos", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${poi.photos.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CategoryBadge(cat: Category, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(cat.color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(cat.iconRes), contentDescription = cat.name, tint = Color.Black.copy(alpha = 0.8f), modifier = Modifier.size((sizeDp * 0.55f).dp))
    }
}

/** View only: the photos, the note's player, category, date and time, coordinates, and Show in map. */
@Composable
fun PoiScreen(dir: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val poi = remember(dir) { PoiStore.read(dir) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(poi?.cat()?.name ?: "POI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (poi == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This POI is gone.") }
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (poi.photos.isNotEmpty()) {
                val pager = rememberPagerState { poi.photos.size }
                HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp))) { i ->
                    AsyncImage(model = poi.photos[i], contentDescription = "Photo ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (poi.photos.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${poi.photos.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            if (poi.note != null) NotePlayer(poi.note, Modifier.fillMaxWidth())
            else Text("No note", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(poi.cat(), 36)
                Spacer(Modifier.width(12.dp))
                Text(poi.cat().name, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(poi.whenText(), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (poi.lat != null && poi.lon != null) String.format(Locale.US, "%.5f, %.5f", poi.lat, poi.lon) else "No coordinates",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            if (poi.lat != null && poi.lon != null) {
                Button(onClick = { showInMap(context, poi.lat, poi.lon) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Show in map")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun showInMap(context: Context, lat: Double, lon: Double) {
    val ll = String.format(Locale.US, "%.5f,%.5f", lat, lon)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No map app installed", Toast.LENGTH_SHORT).show()
    }
}

