package com.lotanbar.tripexplorer.data

import androidx.compose.ui.graphics.Color
import com.lotanbar.tripexplorer.R

/** A category the phone offers: key (written to data.txt), name, icon and color. See the spec's Categories table. */
data class Category(val key: String, val name: String, val iconRes: Int, val color: Color)

object Categories {
    /** The six the phone offers, in grid order (left to right, top to bottom). */
    val all: List<Category> = listOf(
        Category("viewpoints", "Viewpoints", R.drawable.ic_group_viewpoint, Color(0xFF7DA981)),
        Category("religion", "Religion", R.drawable.ic_group_place_of_worship, Color(0xFF90A4AE)),
        Category("food", "Food", R.drawable.ic_cat_food, Color(0xFFD1A54B)),
        Category("history", "History", R.drawable.ic_cat_history, Color(0xFFA97BB0)),
        Category("oddity", "Oddity", R.drawable.ic_cat_oddity, Color(0xFF5FB3AE)),
        Category(NONE, "None", R.drawable.ic_group_marker, Color(0xFFBDBDBD)),
    )

    const val NONE = "none"

    fun byKey(key: String?): Category? = all.firstOrNull { it.key == key }
}
