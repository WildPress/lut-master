package app.lutapply

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookCompositionTest {
    private fun cube(n: Int, f: (Float, Float, Float) -> FloatArray): FloatCube {
        val data = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            f(r / (n - 1f), g / (n - 1f), b / (n - 1f)).copyInto(data, ((b * n + g) * n + r) * 3)
        }
        return FloatCube(n, data)
    }

    private fun load(path: String) = CubeParser.parseFloat(File(path).readText())

    @Test
    fun packPutsRedGreenBlueOnTheRightAxes() {
        val packed = CubeParser.pack(cube(2) { r, g, b -> floatArrayOf(r, g, b) })
        assertEquals(0xFFFF0000.toInt(), packed[1][0][0])
        assertEquals(0xFF00FF00.toInt(), packed[0][1][0])
        assertEquals(0xFF0000FF.toInt(), packed[0][0][1])
        assertEquals(0xFF000000.toInt(), packed[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), packed[1][1][1])
    }

    @Test
    fun realSamsungPlusBambooHazeDoesNotCollapseToIdentity() {
        val samsung = load("src/main/assets/luts/input/samsung-log-to-rec709.cube")
        val bamboo = load("src/main/assets/luts/looks/bamboo-haze.cube")
        val composed = LutMath.compose(samsung, bamboo, 33)
        val samsungOnly = LutMath.compose(samsung, null, 33)
        val out = FloatArray(3)
        val only = FloatArray(3)
        // A Samsung Log foliage green and a mid grey, in log code values.
        for (rgb in listOf(floatArrayOf(0.30f, 0.45f, 0.28f), floatArrayOf(0.4f, 0.4f, 0.4f))) {
            LutMath.sample(composed, rgb[0], rgb[1], rgb[2], out)
            LutMath.sample(samsungOnly, rgb[0], rgb[1], rgb[2], only)
            val fromInput = (0..2).maxOf { kotlin.math.abs(out[it] - rgb[it]) }
            val fromInputOnly = (0..2).maxOf { kotlin.math.abs(out[it] - only[it]) }
            assertTrue("composed ${out.toList()} is too close to the input ${rgb.toList()}", fromInput > 0.05f)
            assertTrue("look adds almost nothing on top of the input transform for ${rgb.toList()}", fromInputOnly > 0.03f)
        }
    }

    @Test
    fun everyBundledLookChangesItsTargetTones() {
        val out = FloatArray(3)
        for (f in File("src/main/assets/luts/looks").listFiles { x -> x.extension == "cube" }!!.sorted()) {
            // The blacks-* looks only touch shadows by design, so probe near-black for them and a mid green for the rest.
            val probe = if (f.name.startsWith("blacks-")) floatArrayOf(0.02f, 0.02f, 0.02f) else floatArrayOf(0.25f, 0.55f, 0.25f)
            LutMath.sample(load(f.path), probe[0], probe[1], probe[2], out)
            val d = (0..2).maxOf { kotlin.math.abs(out[it] - probe[it]) }
            assertTrue("${f.name} barely changes its probe tone (max diff $d)", d > 0.01f)
        }
    }

    @Test
    fun cpuLookupMatchesIdentityAndInvert() {
        val identity = cube(5) { r, g, b -> floatArrayOf(r, g, b) }
        val invert = cube(5) { r, g, b -> floatArrayOf(1 - r, 1 - g, 1 - b) }
        val px = intArrayOf(0xFF102030.toInt(), 0x80FFFFFF.toInt(), 0xFF000000.toInt())
        val a = px.copyOf()
        LutMath.applyToPixels(identity, a)
        for (i in px.indices) assertPixelNear(px[i], a[i], 1)
        val b = px.copyOf()
        LutMath.applyToPixels(invert, b)
        assertPixelNear(0xFFEFDFCF.toInt(), b[0], 1)
        assertPixelNear(0x80000000.toInt(), b[1], 1)
        assertPixelNear(0xFFFFFFFF.toInt(), b[2], 1)
    }

    private fun assertPixelNear(expected: Int, actual: Int, tol: Int) {
        assertEquals("alpha", expected ushr 24, actual ushr 24)
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue("channel at shift $shift expected $e got $a", kotlin.math.abs(e - a) <= tol)
        }
    }
}
