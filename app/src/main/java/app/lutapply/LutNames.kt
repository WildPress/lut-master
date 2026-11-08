package app.lutapply

object LutNames {
    fun pretty(fileName: String): String =
        fileName.removeSuffix(".cube").removeSuffix(".CUBE")
            .split('-', '_', ' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "lut" }
}
