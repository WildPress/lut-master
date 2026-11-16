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
        "Cinematic", "Film", "Slide Film", "Atmospheric", "Vivid", "Beauty", "Moody", "Gritty", "Horror", "Cyberpunk", "Quirky",
        "Vintage", "Cross Processing", "Black & White", "Tinted Blacks", "Foggy Forest", "Underwater",
        "Kawaii", "Glitch", "Thermal",
    )

    // 0.29.0 gave the loose looks folders, merged Moody Landscape into Moody and renamed a few
    // files. Saved favourites (asset paths) and category choices are moved to the new names.
    private val renamed = mapOf(
        "luts/looks/moody-landscape/dark-forest.cube" to "luts/looks/moody/foggy-teal-woods.cube",
        "luts/looks/slide-film/velvia-punch.cube" to "luts/looks/vivid/velvia-punch.cube",
    )
    private val looseFolders = listOf(
        "film" to listOf("kodak-", "fuji-", "cinestill-"),
        "tinted-blacks" to listOf("blacks-"),
        "foggy-forest" to listOf("golden-fog", "bamboo-haze", "neon-forest", "jade-dream", "misty-emerald", "pastel-mist"),
    )

    fun migratePath(path: String): String {
        renamed[path]?.let { return it }
        if (path.startsWith("luts/looks/moody-landscape/")) return path.replace("/moody-landscape/", "/moody/")
        val name = path.removePrefix("luts/looks/")
        if (path.startsWith("luts/looks/") && '/' !in name) {
            looseFolders.firstOrNull { (_, prefixes) -> prefixes.any { name.startsWith(it) } }
                ?.let { return "luts/looks/${it.first}/$name" }
        }
        return path
    }

    fun migrateCategory(title: String): String = if (title == "Moody Landscape") "Moody" else title

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
