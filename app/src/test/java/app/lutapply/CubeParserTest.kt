package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CubeParserTest {
    private val identity2 = """
        # comment
        TITLE "identity"

        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun identityMapsInputToSameColour() {
        val c = CubeParser.parse(identity2)
        assertEquals(2, c.size)
        assertEquals(0xFF000000.toInt(), c[0][0][0])
        assertEquals(0xFFFF0000.toInt(), c[1][0][0])
        assertEquals(0xFF00FF00.toInt(), c[0][1][0])
        assertEquals(0xFF0000FF.toInt(), c[0][0][1])
        assertEquals(0xFFFFFFFF.toInt(), c[1][1][1])
    }

    @Test
    fun redIsFastestAndOutputIsStoredByInputIndex() {
        val swapRB = identity2.lines().map { l ->
            val p = l.trim().split(" ")
            if (p.size == 3 && p[0].toFloatOrNull() != null) "${p[2]} ${p[1]} ${p[0]}" else l
        }.joinToString("\n")
        val c = CubeParser.parse(swapRB)
        // input red (1,0,0) -> output blue
        assertEquals(0xFF0000FF.toInt(), c[1][0][0])
        assertEquals(0xFFFF0000.toInt(), c[0][0][1])
    }

    @Test
    fun rejects1dLut() {
        try {
            CubeParser.parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1")
            fail()
        } catch (e: CubeException) {
            assertTrue(e.message!!.contains("1D"))
        }
    }

    @Test
    fun rejectsWrongRowCount() {
        try {
            CubeParser.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1")
            fail()
        } catch (e: CubeException) {
            assertTrue(e.message!!.contains("8"))
        }
    }

    @Test
    fun rejectsNonUnitDomain() {
        try {
            CubeParser.parse(identity2.replace("DOMAIN_MAX 1 1 1", "DOMAIN_MAX 2 2 2"))
            fail()
        } catch (e: CubeException) {
            assertTrue(e.message!!.contains("DOMAIN"))
        }
    }
}
