package com.lotanbar.tripexplorer.ui.poi

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lotanbar.tripexplorer.data.Groups

/** Group dropdown, same look as the reference app: icon + color in the field and in every item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPicker(selectedKey: String?, onSelected: (String?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selected = Groups.byKey(selectedKey)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.name ?: "No group",
            onValueChange = {},
            readOnly = true,
            label = { Text("Group") },
            leadingIcon = {
                Icon(
                    painter = painterResource(selected?.iconRes ?: Groups.noGroupIcon),
                    contentDescription = null,
                    tint = selected?.color ?: Groups.noGroupColor,
                    modifier = Modifier.size(24.dp),
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(painterResource(Groups.noGroupIcon), null, tint = Groups.noGroupColor, modifier = Modifier.size(24.dp)) },
                text = { Text("No group") },
                onClick = { onSelected(null); expanded = false },
            )
            Groups.all.forEach { group ->
                DropdownMenuItem(
                    leadingIcon = { Icon(painterResource(group.iconRes), null, tint = group.color, modifier = Modifier.size(24.dp)) },
                    text = { Text(group.name, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { onSelected(group.key); expanded = false },
                )
            }
        }
    }
}
