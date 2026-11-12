package app.lutapply

object LookCategories {
    const val MY_LUTS = "My LUTs"
    const val DIAGNOSTICS = "Diagnostics"

    // Looks that sit directly in luts/looks (not in a category folder) are placed by file name.
    private val byPrefix = listOf(
        "Film" to listOf("kodak-", "fuji-", "cinestill-"),
        "Tinted Blacks" to listOf("blacks-"),
        "Foggy Forest" to listOf("golden-fog", "bamboo-haze", "neon-forest", "jade-dream", "misty-emerald", "pastel-mist"),
    )

    private val order = listOf(
        "Cinematic", "Film", "Slide Film", "Atmospheric", "Moody Landscape", "Vivid", "Beauty", "Moody", "Gritty", "Horror", "Cyberpunk", "Quirky",
        "Vintage", "Cross Processing", "Black & White", "Tinted Blacks", "Foggy Forest", "Underwater",
        "Kawaii", "Glitch", "Thermal",
    )

    fun categorise(fileName: String): String =
        byPrefix.firstOrNull { (_, prefixes) -> prefixes.any { fileName.startsWith(it, ignoreCase = true) } }?.first
            ?: "Other"

    fun folderTitle(folder: String): String =
        LutNames.pretty(folder.replace("-and-", "-&-"))

    fun sortKey(title: String): Int = when (title) {
        MY_LUTS -> 900
        DIAGNOSTICS -> 999
        else -> order.indexOf(title).let { if (it < 0) 500 else it }
    }
}
