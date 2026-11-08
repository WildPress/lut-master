package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Test

class LutMathTest {
    private fun cube(n: Int, f: (Float, Float, Float) -> FloatArray): FloatCube {
        val data = FloatArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            val i = ((b * n + g) * n + r) * 3
            o.copyInto(data, i)
        }
        return FloatCube(n, data)
    }

    private val invert = cube(5) { r, g, b -> floatArrayOf(1 - r, 1 - g, 1 - b) }
    private val swapRb = cube(5) { r, g, b -> floatArrayOf(b, g, r) }

    @Test
    fun invertThenInvertIsIdentity() {
        val c = LutMath.compose(invert, invert, 9)
        val out = FloatArray(3)
        LutMath.sample(c, 0.2f, 0.5f, 0.9f, out)
        assertEquals(0.2f, out[0], 0.01f)
        assertEquals(0.5f, out[1], 0.01f)
        assertEquals(0.9f, out[2], 0.01f)
    }

    @Test
    fun orderIsFirstThenSecond() {
        val partial = cube(5) { r, g, b -> floatArrayOf(r, 0f, 0f) }
        val c = LutMath.compose(swapRb, partial, 9)
        val out = FloatArray(3)
        LutMath.sample(c, 1f, 0f, 0.5f, out)
        // swap gives (0.5, 0, 1), then keep red only -> (0.5, 0, 0)
        assertEquals(0.5f, out[0], 0.01f)
        assertEquals(0f, out[1], 0.01f)
        assertEquals(0f, out[2], 0.01f)
    }

    @Test
    fun nullStagesAreIdentity() {
        val c = LutMath.compose(null, null, 5)
        val out = FloatArray(3)
        LutMath.sample(c, 0.3f, 0.6f, 0.9f, out)
        assertEquals(0.3f, out[0], 0.001f)
        assertEquals(0.9f, out[2], 0.001f)
    }
}
