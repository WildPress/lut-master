package app.lutapply

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

// Long-press anywhere over the video (controls included) starts a hold; children get a cancel so the
// press doesn't also count as a tap, and every event until release belongs to the hold.
class HoldToCompareLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var onHoldStart: (() -> Boolean)? = null
    var onHoldEnd: (() -> Unit)? = null
    private var holding = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (onHoldStart?.invoke() != true) return
            holding = true
            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            super@HoldToCompareLayout.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        if (holding) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                holding = false
                onHoldEnd?.invoke()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
