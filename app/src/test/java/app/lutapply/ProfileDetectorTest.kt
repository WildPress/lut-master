package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileDetectorTest {
    @Test
    fun findsSonySlog3InXmlMetadata() {
        val bytes = "\u0000\u0001<Item name=\"CaptureGammaEquation\" value=\"S-Log3-Cine\"/>".toByteArray(Charsets.ISO_8859_1)
        assertEquals("slog3", ProfileDetector.detect(bytes))
    }

    @Test
    fun findsSamsungLogInMdtaKeys() {
        val bytes = "\u0000\u0000\u0000 mdtacom.samsung.android.logvideo\u0000\u0000\u0000&mdtacom.samsung.android.utc_offset".toByteArray(Charsets.ISO_8859_1)
        assertEquals("samsung-log", ProfileDetector.detect(bytes))
    }

    @Test
    fun ignoresSamsungClipWithoutLogKey() {
        // Other S26 clips have the same Samsung boxes but no log-video key.
        val bytes = "\u0000\u0000\u0000&mdtacom.samsung.android.utc_offset\u0000smta\u0000auth\u0000mdlnSM-S948N".toByteArray(Charsets.ISO_8859_1)
        assertNull(ProfileDetector.detect(bytes))
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        assertNull(ProfileDetector.detect(ByteArray(64) { it.toByte() }))
    }
}
