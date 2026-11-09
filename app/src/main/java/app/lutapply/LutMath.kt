package app.lutapply

object LutMath {
    // Trilinear lookup. Writes the output RGB (0..1) into out[0..2].
    fun sample(c: FloatCube, r: Float, g: Float, b: Float, out: FloatArray) {
        val n = c.size
        val max = (n - 1).toFloat()
        val fr = r.coerceIn(0f, 1f) * max
        val fg = g.coerceIn(0f, 1f) * max
        val fb = b.coerceIn(0f, 1f) * max
        val r0 = fr.toInt().coerceAtMost(n - 2)
        val g0 = fg.toInt().coerceAtMost(n - 2)
        val b0 = fb.toInt().coerceAtMost(n - 2)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        out[0] = 0f
        out[1] = 0f
        out[2] = 0f
        for (cb in 0..1) for (cg in 0..1) for (cr in 0..1) {
            val w = (if (cr == 1) dr else 1 - dr) * (if (cg == 1) dg else 1 - dg) * (if (cb == 1) db else 1 - db)
            if (w == 0f) continue
            val i = (((b0 + cb) * n + (g0 + cg)) * n + (r0 + cr)) * 3
            out[0] += w * c.data[i]
            out[1] += w * c.data[i + 1]
            out[2] += w * c.data[i + 2]
        }
    }

    // CPU trilinear lookup over ARGB pixels, in place. Alpha is kept.
    fun applyToPixels(c: FloatCube, pixels: IntArray) {
        val n = c.size
        val scale = (n - 1) / 255f
        val d = c.data
        val sg = n
        val sb = n * n
        for (p in pixels.indices) {
            val px = pixels[p]
            val fr = ((px shr 16) and 0xFF) * scale
            val fg = ((px shr 8) and 0xFF) * scale
            val fb = (px and 0xFF) * scale
            val r0 = minOf(fr.toInt(), n - 2)
            val g0 = minOf(fg.toInt(), n - 2)
            val b0 = minOf(fb.toInt(), n - 2)
            val dr = fr - r0
            val dg = fg - g0
            val db = fb - b0
            val base = (b0 * sb + g0 * sg + r0) * 3
            var accR = 0f
            var accG = 0f
            var accB = 0f
            for (cb in 0..1) for (cg in 0..1) for (cr in 0..1) {
                val w = (if (cr == 1) dr else 1 - dr) * (if (cg == 1) dg else 1 - dg) * (if (cb == 1) db else 1 - db)
                if (w == 0f) continue
                val i = base + (cb * sb + cg * sg + cr) * 3
                accR += w * d[i]
                accG += w * d[i + 1]
                accB += w * d[i + 2]
            }
            val r8 = (accR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g8 = (accG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b8 = (accB * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[p] = (px and 0xFF000000.toInt()) or (r8 shl 16) or (g8 shl 8) or b8
        }
    }

    // Bakes "first, then second" into one n-point cube. A null stage is the identity.
    fun compose(first: FloatCube?, second: FloatCube?, n: Int = 33): FloatCube {
        val data = FloatArray(n * n * n * 3)
        val p = FloatArray(3)
        val q = FloatArray(3)
        val step = (n - 1).toFloat()
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            p[0] = r / step
            p[1] = g / step
            p[2] = b / step
            if (first != null) {
                sample(first, p[0], p[1], p[2], q)
                q.copyInto(p)
            }
            if (second != null) {
                sample(second, p[0], p[1], p[2], q)
                q.copyInto(p)
            }
            val i = ((b * n + g) * n + r) * 3
            data[i] = p[0]
            data[i + 1] = p[1]
            data[i + 2] = p[2]
        }
        return FloatCube(n, data)
    }

    // Blends two cubes of the same size: t = 0 gives a, t = 1 gives b. Used for look strength,
    // where a is the camera profile alone and b is the profile plus the full look.
    fun mix(a: FloatCube, b: FloatCube, t: Float): FloatCube {
        require(a.size == b.size) { "Cubes must be the same size" }
        val out = FloatArray(a.data.size)
        for (i in out.indices) out[i] = a.data[i] + (b.data[i] - a.data[i]) * t
        return FloatCube(a.size, out)
    }
}
