package app.lutapply

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

// Long-press anywhere over the video (controls included) starts a hold; children get a cancel so the
// press doesn't also count as a tap, and every event until release belongs to the hold.
// A sideways drag that starts along the bottom edge, while quickScrubEnabled says so, becomes a
// quick scrub instead: children get a cancel and the drag position is reported as a fraction.
// A plain tap goes to onTap instead of the children (so the player doesn't pop its controls up),
// except a tap that starts on passThrough (the visible control bar), whose buttons need it.
class HoldToCompareLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var onHoldStart: (() -> Boolean)? = null
    var onHoldEnd: (() -> Unit)? = null
    // Gestures that start on this view (the seek bar) never become a hold, so scrubbing can't
    // turn into a compare.
    var exclude: View? = null

    var quickScrubEnabled: (() -> Boolean)? = null
    var onQuickScrubStart: (() -> Unit)? = null
    var onQuickScrubTo: ((Float) -> Unit)? = null
    var onQuickScrubEnd: (() -> Unit)? = null

    // Returns true when it handled the tap.
    var onTap: (() -> Boolean)? = null
    var passThrough: View? = null

    private var holding = false
    private var excluded = false
    private var scrubCandidate = false
    private var scrubbing = false
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    // How far up from the bottom edge a drag can start and still scrub. Fingers aren't thin.
    private val scrubBand = 48 * resources.displayMetrics.density

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (scrubbing || onHoldStart?.invoke() != true) return
            holding = true
            tapCandidate = false
            cancelChildren(e)
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                scrubbing = false
                scrubCandidate = ev.y >= height - scrubBand && quickScrubEnabled?.invoke() == true
                excluded = scrubCandidate || startsOn(exclude, ev)
                tapCandidate = onTap != null && !startsOn(exclude, ev) && !startsOn(passThrough, ev)
            }
            MotionEvent.ACTION_MOVE -> if (tapCandidate &&
                (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                tapCandidate = false
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (scrubCandidate && !scrubbing) {
                val dx = abs(ev.x - downX)
                if (dx > touchSlop && dx > abs(ev.y - downY)) {
                    scrubbing = true
                    cancelChildren(ev)
                    onQuickScrubStart?.invoke()
                }
            }
        }
        if (scrubbing) {
            onQuickScrubTo?.invoke((ev.x / width).coerceIn(0f, 1f))
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                scrubbing = false
                scrubCandidate = false
                onQuickScrubEnd?.invoke()
            }
            return true
        }
        if (!excluded) detector.onTouchEvent(ev)
        if (holding) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                holding = false
                onHoldEnd?.invoke()
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP && tapCandidate) {
            tapCandidate = false
            if (onTap?.invoke() == true) {
                cancelChildren(ev)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelChildren(e: MotionEvent) {
        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
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
