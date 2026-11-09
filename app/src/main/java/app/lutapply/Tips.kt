package app.lutapply

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.StringRes

// First-run hints: a bubble next to a control, shown once each and one at a time. Tapping
// anywhere dismisses it.
class Tips(private val activity: Activity, private val prefs: SharedPreferences) {
    enum class Place { ABOVE, BELOW, INSIDE_TOP }

    private class Tip(val key: String, val anchor: View, @StringRes val text: Int, val place: Place)

    private val queue = ArrayDeque<Tip>()
    private var current: Tip? = null
    private var popup: PopupWindow? = null

    fun show(key: String, anchor: View, @StringRes text: Int, place: Place) {
        if (seen(key) || current?.key == key || queue.any { it.key == key }) return
        queue.addLast(Tip(key, anchor, text, place))
        if (current == null) next()
    }

    fun dismiss() {
        queue.clear()
        popup?.dismiss()
    }

    private fun seen(key: String) = prefs.getBoolean("tip_$key", false)

    private fun next() {
        val tip = queue.removeFirstOrNull() ?: return
        current = tip
        // Wait for the anchor to be laid out, and give the screen a moment to settle.
        tip.anchor.postDelayed({ display(tip) }, 400)
    }

    private fun display(tip: Tip) {
        val a = tip.anchor
        if (activity.isFinishing || activity.isDestroyed || !a.isShown || a.width == 0 || a.windowToken == null) {
            // Not on screen right now: try again the next time it's asked for.
            current = null
            next()
            return
        }
        val d = activity.resources.displayMetrics.density
        val content = LayoutInflater.from(activity).inflate(R.layout.tip_bubble, null)
        content.findViewById<TextView>(R.id.tipText).setText(tip.text)
        val up = content.findViewById<ImageView>(R.id.tipArrowUp)
        val down = content.findViewById<ImageView>(R.id.tipArrowDown)
        up.visibility = if (tip.place == Place.BELOW) View.VISIBLE else View.GONE
        down.visibility = if (tip.place == Place.ABOVE) View.VISIBLE else View.GONE

        val root = activity.window.decorView
        val maxW = minOf(root.width - (32 * d).toInt(), (320 * d).toInt())
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = content.measuredWidth
        val h = content.measuredHeight
        val loc = IntArray(2)
        a.getLocationInWindow(loc)
        val centreX = loc[0] + a.width / 2
        val margin = (16 * d).toInt()
        val x = (centreX - w / 2).coerceIn(margin, maxOf(margin, root.width - w - margin))
        val y = when (tip.place) {
            Place.BELOW -> loc[1] + a.height
            Place.ABOVE -> loc[1] - h
            Place.INSIDE_TOP -> loc[1] + (12 * d).toInt()
        }
        // Point the arrow at the anchor's centre.
        val arrowW = (16 * d).toInt()
        val arrowX = (centreX - x - arrowW / 2).toFloat().coerceIn(12 * d, w - 12 * d - arrowW)
        up.translationX = arrowX
        down.translationX = arrowX

        val window = PopupWindow(content, w, h, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8 * d
            animationStyle = android.R.style.Animation_Dialog
            setOnDismissListener {
                prefs.edit().putBoolean("tip_${tip.key}", true).apply()
                popup = null
                current = null
                next()
            }
        }
        content.setOnClickListener { window.dismiss() }
        popup = window
        window.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    }
}
