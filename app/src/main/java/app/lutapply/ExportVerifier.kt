package app.lutapply

import kotlin.math.abs

// Decides whether an exported frame looks like the source with the LUT applied, or like the untouched source.
// All pixel arrays are ARGB ints of the same size. Differences are mean absolute RGB error on a 0..255 scale.
object ExportVerifier {
    enum class Verdict { APPLIED, UNCHANGED, INCONCLUSIVE }

    class Result(val verdict: Verdict, val outVsLut: Float, val outVsRaw: Float, val lutVsRaw: Float)

    // Below this the look barely changes the picture, so the two cases cannot be told apart.
    private const val MIN_EFFECT = 3f
    private const val DECISIVE = 0.6f

    fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "Pixel arrays differ in size" }
        var sum = 0L
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            sum += abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)) +
                abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)) +
                abs((x and 0xFF) - (y and 0xFF))
        }
        return sum.toFloat() / (a.size * 3f)
    }

    fun check(source: IntArray, output: IntArray, lut: FloatCube): Result {
        val graded = source.copyOf()
        LutMath.applyToPixels(lut, graded)
        val lutVsRaw = meanAbsDiff(graded, source)
        val outVsLut = meanAbsDiff(output, graded)
        val outVsRaw = meanAbsDiff(output, source)
        val verdict = when {
            lutVsRaw < MIN_EFFECT -> Verdict.INCONCLUSIVE
            outVsLut < outVsRaw * DECISIVE -> Verdict.APPLIED
            outVsRaw < outVsLut * DECISIVE -> Verdict.UNCHANGED
            else -> Verdict.INCONCLUSIVE
        }
        return Result(verdict, outVsLut, outVsRaw, lutVsRaw)
    }

    fun describe(r: Result): String {
        val numbers = "out vs LUT %.1f, out vs raw %.1f, LUT vs raw %.1f".format(r.outVsLut, r.outVsRaw, r.lutVsRaw)
        return when (r.verdict) {
            Verdict.APPLIED -> "Export verified: LUT applied ($numbers)"
            Verdict.UNCHANGED -> "WARNING: export looks unchanged, effect not applied ($numbers)"
            Verdict.INCONCLUSIVE ->
                if (r.lutVsRaw < MIN_EFFECT) "Export check inconclusive: this look changes the frame very little ($numbers)"
                else "Export check inconclusive: output matches neither the raw nor the graded frame ($numbers)"
        }
    }
}
