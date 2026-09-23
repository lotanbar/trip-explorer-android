package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.ui.theme.resolvedTextAlign
import com.lotanbar.tripexplorer.ui.theme.resolvedTextDirection

/** Name, description and group: the fields shared by Add POI and the POI editor. */
@Composable
fun PoiFields(
    name: String,
    onName: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescription: (String) -> Unit,
    groupKey: String?,
    onGroup: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("Name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = name.resolvedTextDirection(), textAlign = name.resolvedTextAlign()),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = onDescription,
            label = { Text("Description") },
            minLines = 2,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = description.resolvedTextDirection(), textAlign = description.resolvedTextAlign()),
            modifier = Modifier.fillMaxWidth(),
        )
        GroupPicker(selectedKey = groupKey, onSelected = onGroup, modifier = Modifier.fillMaxWidth())
    }
}

