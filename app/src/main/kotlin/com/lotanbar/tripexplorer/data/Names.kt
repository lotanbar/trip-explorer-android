package com.lotanbar.tripexplorer.data

/**
 * Name rules from the spec: folders must copy to Windows unchanged, so trip names that Windows
 * can't use are refused with a message. Nothing is replaced silently. (POI names are made by the app.)
 */
object Names {
    const val RECORDINGS_FOLDER = "recordings"
    /** trips/plans/ holds plan files (made on the PC); it is not a trip, so a trip can't take the name. */
    const val PLANS_FOLDER = "plans"

    private const val FORBIDDEN = "\\/:*?\"<>|"
    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }

    /**
     * Returns null when the trip name [name] is allowed, otherwise the message to show.
     * [taken] holds the other trips' names, compared case-insensitively because Windows folders are.
     */
    fun check(name: String, taken: Collection<String> = emptyList()): String? {
        if (name.isEmpty()) return "A name is required."
        val bad = name.firstOrNull { it in FORBIDDEN }
        if (bad != null) return "$bad isn't allowed — Windows can't use it in folder names."
        if (name.any { it < ' ' }) return "Control characters aren't allowed in folder names."
        if (name.endsWith('.')) return "A name can't end with a dot — Windows drops it."
        if (name.endsWith(' ')) return "A name can't end with a space — Windows drops it."
        if (name.startsWith(' ')) return "A name can't start with a space."
        if (name.uppercase() in RESERVED) return "$name is a reserved name on Windows."
        if (name.equals(PLANS_FOLDER, ignoreCase = true)) return "\"plans\" is reserved for the plans folder."
        if (taken.any { it.equals(name, ignoreCase = true) }) return "A trip named \"$name\" already exists."
        return null
    }
}
