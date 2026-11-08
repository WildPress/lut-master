package app.lutapply

import kotlin.math.roundToInt

class CubeException(message: String) : Exception(message)

// data holds RGB floats in .cube file order (red fastest): index ((b * size + g) * size + r) * 3.
class FloatCube(val size: Int, val data: FloatArray)

object CubeParser {
    fun parse(text: String): Array<Array<IntArray>> = pack(parseFloat(text))

    // Returns cube[r][g][b] = ARGB output colour for input (r, g, b), the layout SingleColorLut.createFromCube expects.
    fun pack(cube: FloatCube): Array<Array<IntArray>> {
        val n = cube.size
        val out = Array(n) { Array(n) { IntArray(n) } }
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val i = ((b * n + g) * n + r) * 3
            out[r][g][b] = 0xFF000000.toInt() or
                (to8(cube.data[i]) shl 16) or (to8(cube.data[i + 1]) shl 8) or to8(cube.data[i + 2])
        }
        return out
    }

    fun parseFloat(text: String): FloatCube {
        var size3d = 0
        var size1d = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val rows = ArrayList<FloatArray>()

        for ((index, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> {}
                "LUT_3D_SIZE" -> size3d = parts.getOrNull(1)?.toIntOrNull()
                    ?: throw CubeException("Line ${index + 1}: bad LUT_3D_SIZE")
                "LUT_1D_SIZE" -> size1d = parts.getOrNull(1)?.toIntOrNull()
                    ?: throw CubeException("Line ${index + 1}: bad LUT_1D_SIZE")
                "DOMAIN_MIN" -> domainMin = parseTriple(parts, index)
                "DOMAIN_MAX" -> domainMax = parseTriple(parts, index)
                else -> {
                    if (parts[0][0].isLetter()) continue
                    rows.add(parseTriple(parts, index, offset = 0))
                }
            }
        }

        if (size3d == 0) {
            if (size1d > 0) throw CubeException("This is a 1D LUT. Only 3D LUTs (LUT_3D_SIZE) are supported.")
            throw CubeException("No LUT_3D_SIZE found. Not a valid .cube file.")
        }
        if (size3d < 2 || size3d > 129) throw CubeException("Unsupported LUT_3D_SIZE $size3d.")
        // SingleColorLut samples with input 0..1, so a custom input domain cannot be represented.
        if (domainMin.any { it != 0f } || domainMax.any { it != 1f }) {
            throw CubeException("LUT has a DOMAIN_MIN/MAX other than 0..1, which is not supported.")
        }

        val skip = if (size1d > 0) size1d else 0
        val expected = size3d * size3d * size3d
        if (rows.size != skip + expected) {
            throw CubeException("Expected ${skip + expected} data rows but found ${rows.size}.")
        }

        val data = FloatArray(expected * 3)
        for (i in 0 until expected) {
            val v = rows[skip + i]
            data[i * 3] = v[0].coerceIn(0f, 1f)
            data[i * 3 + 1] = v[1].coerceIn(0f, 1f)
            data[i * 3 + 2] = v[2].coerceIn(0f, 1f)
        }
        return FloatCube(size3d, data)
    }

    private fun to8(x: Float): Int = (x.coerceIn(0f, 1f) * 255f).roundToInt()

    private fun parseTriple(parts: List<String>, index: Int, offset: Int = 1): FloatArray {
        if (parts.size < offset + 3) throw CubeException("Line ${index + 1}: expected 3 numbers.")
        return FloatArray(3) {
            parts[offset + it].toFloatOrNull() ?: throw CubeException("Line ${index + 1}: bad number '${parts[offset + it]}'.")
        }
    }
}
