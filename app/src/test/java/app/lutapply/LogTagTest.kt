package app.lutapply

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class LogTagTest {
    private fun mdta(key: String) = MdtaMetadataEntry(key, "1".toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING)

    @Test
    fun dropsSamsungLogTagAndKeepsTheRest() {
        val fps = mdta("com.samsung.android.utc_offset")
        val meta = Metadata(0L, mdta("com.samsung.android.logvideo"), fps)
        val out = LogTag.strip(meta)!!
        assertEquals(1, out.length())
        assertEquals(fps, out[0])
    }

    @Test
    fun onlyTheLogTagLeavesNoMetadata() {
        assertNull(LogTag.strip(Metadata(0L, mdta("com.samsung.android.logvideo"))))
        assertNull(LogTag.strip(null))
    }
}
