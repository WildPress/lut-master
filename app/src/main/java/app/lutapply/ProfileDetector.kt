package app.lutapply

// Finds the camera profile from text that cameras embed in the file's metadata.
// Each marker maps to a fragment of the matching input LUT's file name.
object ProfileDetector {
    // Sony writes CaptureGammaEquation="s-log3-cine" (or "s-log3") in the clip's XML metadata.
    // Not yet checked against a real a7C II file.
    // Samsung Log clips carry the metadata key "com.samsung.android.logvideo" and a
    // "Log_Video_Version_Info" block. Checked on eight S26 Ultra clips (SM-S948N), November 2026:
    // six original camera clips had both. Two "_1" copies (most likely edited in the Gallery) had neither.
    private val markers = listOf(
        "com.samsung.android.logvideo" to "samsung-log",
        "log_video_version_info" to "samsung-log",
        "s-log3" to "slog3",
    )

    fun detect(metadata: ByteArray): String? {
        val text = String(metadata, Charsets.ISO_8859_1).lowercase()
        return markers.firstOrNull { text.contains(it.first) }?.second
    }
}
