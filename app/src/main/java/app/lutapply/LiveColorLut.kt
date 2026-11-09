package app.lutapply

import android.graphics.Bitmap
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import java.util.concurrent.atomic.AtomicReference

// A colour LUT whose contents can change while the video plays.
// Swapping SingleColorLut effects with setVideoEffects rebuilds the whole GPU pipeline on every
// change, which made the preview jump and, after a few quick changes, stop picking up new looks.
// This effect stays in the pipeline and uploads the new LUT on the GL thread before the next frame.
@UnstableApi
class LiveColorLut(initial: Array<Array<IntArray>>) : ColorLut {
    private val pending = AtomicReference<Bitmap?>(toBitmap(initial))

    // Only touched on the GL thread.
    private var current: Bitmap? = null
    private var textureId = Format.NO_VALUE

    // Called on the main thread. Takes effect on the next frame drawn.
    fun set(cube: Array<Array<IntArray>>) {
        pending.set(toBitmap(cube))
    }

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        val next = pending.getAndSet(null)
        if (next != null || textureId == Format.NO_VALUE) {
            if (next != null) current = next
            if (textureId != Format.NO_VALUE) GlUtil.deleteTexture(textureId)
            textureId = GlUtil.createTexture(current!!)
        }
        return textureId
    }

    // The shader reads this straight after getLutTextureId, so it matches the texture just bound.
    override fun getLength(presentationTimeUs: Long): Int = (current ?: pending.get())!!.width

    // The pipeline is being torn down (new video, diagnostics, error). Keep the bitmap so the same
    // effect can be used again in the next pipeline, which will recreate the texture.
    override fun release() {
        if (textureId != Format.NO_VALUE) GlUtil.deleteTexture(textureId)
        textureId = Format.NO_VALUE
    }

    companion object {
        // Same layout as SingleColorLut: N planes of N x N stacked vertically into an N x N² bitmap.
        fun toBitmap(cube: Array<Array<IntArray>>): Bitmap {
            val n = cube.size
            val pixels = IntArray(n * n * n)
            for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
                pixels[b + n * (g + n * r)] = cube[r][g][b]
            }
            return Bitmap.createBitmap(pixels, n, n * n, Bitmap.Config.ARGB_8888)
        }
    }
}
