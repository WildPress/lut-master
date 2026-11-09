package app.lutapply

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LutStackTest {
    // A 2-point cube that applies f to each channel independently.
    private fun curve(f: (Float) -> Float): FloatCube {
        val data = FloatArray(2 * 2 * 2 * 3)
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            val i = ((b * 2 + g) * 2 + r) * 3
            data[i] = f(r.toFloat())
            data[i + 1] = f(g.toFloat())
            data[i + 2] = f(b.toFloat())
        }
        return FloatCube(2, data)
    }

    private fun at(c: FloatCube, v: Float): Float {
        val out = FloatArray(3)
        LutMath.sample(c, v, v, v, out)
        return out[0]
    }

    @Test
    fun noLayersIsTheInput() {
        val input = curve { 0.1f + 0.8f * it }
        assertArrayEquals(LutMath.compose(input, null, 9).data, LutMath.bake(input, emptyList(), 9).data, 1e-6f)
    }

    @Test
    fun layersApplyBottomFirst() {
        val half = curve { it * 0.5f }
        val lift = curve { 0.2f + 0.8f * it }
        // half then lift: 1 -> 0.5 -> 0.6. lift then half: 1 -> 1 -> 0.5.
        assertEquals(0.6f, at(LutMath.bake(null, listOf(half to 1f, lift to 1f), 9), 1f), 1e-5f)
        assertEquals(0.5f, at(LutMath.bake(null, listOf(lift to 1f, half to 1f), 9), 1f), 1e-5f)
    }

    @Test
    fun layerStrengthBlendsWithWhatIsBelow() {
        val half = curve { it * 0.5f }
        assertEquals(0.75f, at(LutMath.bake(null, listOf(half to 0.5f), 9), 1f), 1e-5f)
        assertEquals(1f, at(LutMath.bake(null, listOf(half to 0f), 9), 1f), 1e-5f)
    }
}
