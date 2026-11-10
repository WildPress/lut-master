package app.lutapply

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput

// Removes colour metadata from every track so ExoPlayer's effect pipeline treats the video as plain
// SDR BT.709 and the LUT sees the raw decoded code values. Without this, S-Log3 clips flagged
// BT.2020/HLG would run the HDR graph, where SingleColorLut throws "HDR is currently not supported".
@UnstableApi
class StripColorInfoExtractorsFactory(base: ExtractorsFactory) :
    FormatFilterExtractorsFactory(base, { it.buildUpon().setColorInfo(null).build() })

// Removes the camera's "this is a Log clip" tag before export. The output is already graded, but
// Media3 copies the source's metadata into it, and Samsung's Gallery then shows it as LOG and
// applies its own Log conversion on top in the editor.
@UnstableApi
class StripLogTagExtractorsFactory(base: ExtractorsFactory) :
    FormatFilterExtractorsFactory(base, { f -> f.buildUpon().setMetadata(LogTag.strip(f.metadata)).build() })

@UnstableApi
object LogTag {
    fun isLogTag(entry: Metadata.Entry): Boolean {
        val s = entry.toString().lowercase()
        return "logvideo" in s || "log_video" in s
    }

    fun strip(metadata: Metadata?): Metadata? {
        if (metadata == null) return null
        val kept = (0 until metadata.length()).map { metadata[it] }.filterNot(::isLogTag)
        return if (kept.isEmpty()) null else Metadata(metadata.presentationTimeUs, kept)
    }
}

// Rewrites each track's format as the file is read.
@UnstableApi
open class FormatFilterExtractorsFactory(
    base: ExtractorsFactory,
    private val transform: (Format) -> Format,
) : ForwardingExtractorsFactory(base) {
    override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(super.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            object : ForwardingExtractor(extractors[i]) {
                override fun init(output: ExtractorOutput) {
                    super.init(object : ForwardingExtractorOutput(output) {
                        override fun track(id: Int, type: Int): TrackOutput =
                            object : ForwardingTrackOutput(super.track(id, type)) {
                                override fun format(format: Format) {
                                    super.format(transform(format))
                                }
                            }
                    })
                }
            }
        }
}
