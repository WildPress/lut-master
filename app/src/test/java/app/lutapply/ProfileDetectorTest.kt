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
    fun returnsNullWhenNothingMatches() {
        assertNull(ProfileDetector.detect(ByteArray(64) { it.toByte() }))
    }
}
