package app.lutapply

object LookCategories {
    data class Category(val title: String, val prefixes: List<String>)

    private val categories = listOf(
        Category("Film", listOf("kodak-", "fuji-", "cinestill-")),
        Category("Tinted Blacks", listOf("blacks-")),
        Category("Foggy Forest", listOf("golden-fog", "bamboo-haze", "neon-forest", "jade-dream", "misty-emerald", "pastel-mist")),
    )

    fun categorise(path: String): String {
        val file = path.substringAfterLast('/')
        for (cat in categories) {
            if (cat.prefixes.any { file.startsWith(it, ignoreCase = true) }) return cat.title
        }
        return "Other"
    }

    fun groupOrder(title: String): Int = when (title) {
        "Film" -> 0
        "Tinted Blacks" -> 1
        "Foggy Forest" -> 2
        "External" -> 3
        "Diagnostics" -> 4
        else -> 3
    }
}
