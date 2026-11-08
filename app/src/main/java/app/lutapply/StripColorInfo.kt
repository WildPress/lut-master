package app.lutapply

import android.net.Uri
import androidx.media3.common.Format
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
class StripColorInfoExtractorsFactory(base: ExtractorsFactory) : ForwardingExtractorsFactory(base) {
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
                                    super.format(format.buildUpon().setColorInfo(null).build())
                                }
                            }
                    })
                }
            }
        }
}
