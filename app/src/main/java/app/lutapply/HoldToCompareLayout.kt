package app.lutapply

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

// Long-press anywhere over the video (controls included) starts a hold; children get a cancel so the
// press doesn't also count as a tap, and every event until release belongs to the hold.
class HoldToCompareLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var onHoldStart: (() -> Boolean)? = null
    var onHoldEnd: (() -> Unit)? = null
    // Gestures that start on this view (the seek bar) never become a hold, so scrubbing can't
    // turn into a compare.
    var exclude: View? = null
    private var holding = false
    private var excluded = false

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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) excluded = startsOn(exclude, ev)
        if (!excluded) detector.onTouchEvent(ev)
        if (holding) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                holding = false
                onHoldEnd?.invoke()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun startsOn(v: View?, ev: MotionEvent): Boolean {
        if (v == null || !v.isShown) return false
        val mine = IntArray(2)
        val theirs = IntArray(2)
        getLocationOnScreen(mine)
        v.getLocationOnScreen(theirs)
        val x = ev.x + mine[0] - theirs[0]
        val y = ev.y + mine[1] - theirs[1]
        // A little extra height: the bar is thin and fingers aren't.
        val slop = 16 * resources.displayMetrics.density
        return x >= 0 && x <= v.width && y >= -slop && y <= v.height + slop
    }
}
