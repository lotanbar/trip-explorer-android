package com.lotanbar.tripexplorer.data

/**
 * Name rules from the spec: folders must copy to Windows unchanged, so trip and POI names that
 * Windows can't use are refused with a message. Nothing is replaced silently.
 */
object Names {
    const val RECORDINGS_FOLDER = "recordings"
    /** trips/plans/ holds plan files; it is not a trip, so a trip can't take the name. */
    const val PLANS_FOLDER = "plans"

    private const val FORBIDDEN = "\\/:*?\"<>|"
    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }

    /**
     * Returns null when [name] is allowed, otherwise the message to show.
     * [taken] holds the names already used at the same level (other trips, or other POIs in the trip),
     * compared case-insensitively because Windows folders are.
     */
    fun check(name: String, taken: Collection<String> = emptyList(), isPoi: Boolean = false): String? {
        if (name.isEmpty()) return "A name is required."
        val bad = name.firstOrNull { it in FORBIDDEN }
        if (bad != null) return "$bad isn't allowed — Windows can't use it in folder names."
        if (name.any { it < ' ' }) return "Control characters aren't allowed in folder names."
        if (name.endsWith('.')) return "A name can't end with a dot — Windows drops it."
        if (name.endsWith(' ')) return "A name can't end with a space — Windows drops it."
        if (name.startsWith(' ')) return "A name can't start with a space."
        if (name.uppercase() in RESERVED) return "$name is a reserved name on Windows."
        if (isPoi && name.equals(RECORDINGS_FOLDER, ignoreCase = true)) {
            return "\"recordings\" is reserved for the recordings folder."
        }
        if (!isPoi && name.equals(PLANS_FOLDER, ignoreCase = true)) {
            return "\"plans\" is reserved for the plans folder."
        }
        if (taken.any { it.equals(name, ignoreCase = true) }) {
            return if (isPoi) "A POI named \"$name\" already exists in this trip." else "A trip named \"$name\" already exists."
        }
        return null
    }
}
