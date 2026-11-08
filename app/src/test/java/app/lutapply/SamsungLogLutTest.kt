package app.lutapply

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SamsungLogLutTest {
    private val file = File("src/main/assets/luts/input/samsung-log-to-rec709.cube")
    private val cube by lazy { CubeParser.parseFloat(file.readText()) }

    @Before
    fun requireSamsungLut() {
        assumeTrue("Samsung LUT is not bundled in this checkout", file.exists())
    }

    @Test
    fun parsesAs65PointGrid() {
        assertEquals(65, cube.size)
        assertEquals(274625, cube.data.size / 3)
    }

    @Test
    fun blackMapsNearZeroAndWhiteNearOne() {
        val out = FloatArray(3)
        LutMath.sample(cube, 0f, 0f, 0f, out)
        assertTrue("black -> ${out.toList()}", out.all { it < 0.02f })
        LutMath.sample(cube, 1f, 1f, 1f, out)
        assertTrue("white -> ${out.toList()}", out.all { it > 0.95f })
    }

    @Test
    fun composesTo33PointWithMonotonicGreyRamp() {
        val c33 = LutMath.compose(cube, null, 33)
        assertEquals(33, c33.size)
        var prev = -1f
        for (i in 0 until 33) {
            val idx = ((i * 33 + i) * 33 + i) * 3
            assertTrue("grey ramp not monotonic at $i", c33.data[idx] >= prev)
            prev = c33.data[idx]
        }
    }
}
