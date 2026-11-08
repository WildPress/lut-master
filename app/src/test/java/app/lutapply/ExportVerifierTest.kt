package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportVerifierTest {
    private fun cube(n: Int, f: (Float, Float, Float) -> FloatArray): FloatCube {
        val data = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            f(r / (n - 1f), g / (n - 1f), b / (n - 1f)).copyInto(data, ((b * n + g) * n + r) * 3)
        }
        return FloatCube(n, data)
    }

    private val invert = cube(9) { r, g, b -> floatArrayOf(1 - r, 1 - g, 1 - b) }
    private val identity = cube(9) { r, g, b -> floatArrayOf(r, g, b) }

    private val source = IntArray(32 * 32) { i ->
        val r = (i * 7) % 256
        val g = (i * 13 + 40) % 256
        val b = (i * 29 + 90) % 256
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun graded(lut: FloatCube) = source.copyOf().also { LutMath.applyToPixels(lut, it) }

    private fun noisy(px: IntArray, amount: Int) = IntArray(px.size) { i ->
        val d = if (i % 2 == 0) amount else -amount
        fun ch(shift: Int) = (((px[i] shr shift) and 0xFF) + d).coerceIn(0, 255)
        0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test
    fun gradedOutputIsVerified() {
        val r = ExportVerifier.check(source, noisy(graded(invert), 3), invert)
        assertEquals(ExportVerifier.Verdict.APPLIED, r.verdict)
    }

    @Test
    fun untouchedOutputIsFlagged() {
        val r = ExportVerifier.check(source, noisy(source, 3), invert)
        assertEquals(ExportVerifier.Verdict.UNCHANGED, r.verdict)
    }

    @Test
    fun halfwayOutputIsInconclusive() {
        val g = graded(invert)
        val half = IntArray(source.size) { i ->
            fun ch(shift: Int) = (((source[i] shr shift) and 0xFF) + ((g[i] shr shift) and 0xFF)) / 2
            0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        assertEquals(ExportVerifier.Verdict.INCONCLUSIVE, ExportVerifier.check(source, half, invert).verdict)
    }

    @Test
    fun identityLookCannotBeJudged() {
        val r = ExportVerifier.check(source, source, identity)
        assertEquals(ExportVerifier.Verdict.INCONCLUSIVE, r.verdict)
        assertTrue(ExportVerifier.describe(r).contains("very little"))
    }

    @Test
    fun descriptionsCarryTheMarkers() {
        val applied = ExportVerifier.describe(ExportVerifier.check(source, graded(invert), invert))
        val unchanged = ExportVerifier.describe(ExportVerifier.check(source, source, invert))
        assertTrue(applied.startsWith("Export verified: LUT applied"))
        assertTrue(unchanged.startsWith("WARNING: export looks unchanged, effect not applied"))
    }
}
