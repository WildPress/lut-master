package app.lutapply

// Finds the camera profile from text that cameras embed in the file's metadata.
// Each marker maps to a fragment of the matching input LUT's file name.
object ProfileDetector {
    // Sony writes CaptureGammaEquation="s-log3-cine" (or "s-log3") in the clip's XML metadata.
    // Not yet checked against a real a7C II file.
    private val markers = listOf(
        "s-log3" to "slog3",
    )

    fun detect(metadata: ByteArray): String? {
        val text = String(metadata, Charsets.ISO_8859_1).lowercase()
        return markers.firstOrNull { text.contains(it.first) }?.second
    }
}
