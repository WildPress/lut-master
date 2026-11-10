package app.lutapply

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

// As tall as it is wide, so look tiles are square whatever shape the video is.
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val exact = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
    }
}
