package com.lotanbar.tripexplorer.data

import androidx.compose.ui.graphics.Color
import com.lotanbar.tripexplorer.R

/** A fixed group: name, folder file, icon and color. The list is the spec's table, minus the OSM-only groups. */
data class Group(val key: String, val name: String, val iconRes: Int, val color: Color) {
    val fileName: String get() = "group-$key.txt"
}

object Groups {
    val all: List<Group> = listOf(
        Group("caves", "Caves", R.drawable.ic_group_cave, Color(0xFF8F7FB8)),
        Group("water", "Water", R.drawable.ic_group_waterfall, Color(0xFF5FA3C9)),
        Group("geology", "Geology", R.drawable.ic_group_volcano, Color(0xFFD3906F)),
        Group("archaeology", "Archaeology", R.drawable.ic_group_column, Color(0xFFC9A86A)),
        Group("fortifications", "Fortifications", R.drawable.ic_group_shield, Color(0xFFC46B6B)),
        Group("religion", "Religion", R.drawable.ic_group_place_of_worship, Color(0xFF90A4AE)),
        Group("structures", "Structures", R.drawable.ic_group_archway, Color(0xFF9B857B)),
        Group("abandoned", "Abandoned", R.drawable.ic_group_ghost, Color(0xFF9E9E9E)),
        Group("viewpoints", "Viewpoints", R.drawable.ic_group_viewpoint, Color(0xFF7DA981)),
    )

    /** "No group" look: the marker icon in light grey. */
    val noGroupIcon = R.drawable.ic_group_marker
    val noGroupColor = Color(0xFFBDBDBD)

    fun byKey(key: String?): Group? = all.firstOrNull { it.key == key }
}
