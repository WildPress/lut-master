package app.lutapply

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LutMixTest {
    private fun cube(vararg v: Float) = FloatCube(1, floatArrayOf(*v))

    @Test
    fun zeroStrengthIsTheBase() {
        val out = LutMath.mix(cube(0.2f, 0.4f, 0.6f), cube(1f, 0f, 0.5f), 0f)
        assertArrayEquals(floatArrayOf(0.2f, 0.4f, 0.6f), out.data, 1e-6f)
    }

    @Test
    fun fullStrengthIsTheLook() {
        val out = LutMath.mix(cube(0.2f, 0.4f, 0.6f), cube(1f, 0f, 0.5f), 1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0.5f), out.data, 1e-6f)
    }

    @Test
    fun halfStrengthIsHalfway() {
        val out = LutMath.mix(cube(0.2f, 0.4f, 0.6f), cube(1f, 0f, 0.5f), 0.5f)
        assertArrayEquals(floatArrayOf(0.6f, 0.2f, 0.55f), out.data, 1e-6f)
    }
}
