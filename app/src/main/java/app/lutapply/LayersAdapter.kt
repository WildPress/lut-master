package app.lutapply

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

// One layer in the stack: which look it uses (index into the looks list, 0 = none) and how strongly.
class Layer(var look: Int, var strength: Float = 1f)

// The layer stack in display order: row 0 is the top layer, which applies last.
class LayersAdapter(
    private val layers: MutableList<Layer>,
    private val nameOf: (Layer) -> String?,
    private val onSelect: (Int) -> Unit,
    private val onStrength: (Int, Float) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onMoved: () -> Unit,
) : RecyclerView.Adapter<LayersAdapter.VH>() {

    var selected = 0
        private set

    fun select(index: Int) {
        val old = selected
        selected = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = true

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            val selectedLayer = layers[selected]
            layers.add(b, layers.removeAt(a))
            // Plain assignment: rebinding a row mid-drag would cancel the drag. The highlight moves
            // with the row's view anyway.
            selected = layers.indexOf(selectedLayer)
            notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        // The grade is rebuilt once, when the row is dropped, not on every step of the drag.
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            onMoved()
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.85f
        }
    })

    // After adding or removing a layer every row can change (handles, remove buttons, highlight).
    @SuppressLint("NotifyDataSetChanged")
    fun reset(selectedIndex: Int) {
        selected = selectedIndex
        notifyDataSetChanged()
    }

    fun attach(rv: RecyclerView) {
        rv.adapter = this
        touchHelper.attachToRecyclerView(rv)
    }

    override fun getItemCount() = layers.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_layer, parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(vh: VH, position: Int) {
        val layer = layers[position]
        val name = nameOf(layer)
        val ctx = vh.itemView.context
        vh.itemView.isActivated = position == selected
        vh.name.text = name ?: ctx.getString(R.string.empty_layer)
        vh.name.alpha = if (name == null) 0.6f else 1f
        val pct = (layer.strength * 100).roundToInt()
        vh.slider.clearOnChangeListeners()
        vh.slider.value = (pct / 5 * 5).toFloat()
        vh.slider.isEnabled = name != null
        vh.pct.text = if (name != null) "$pct%" else ""
        vh.slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@addOnChangeListener
            vh.pct.text = "${value.roundToInt()}%"
            onStrength(pos, value / 100f)
        }
        // Touching a slider also picks that layer, so the grid and the slider agree.
        vh.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos != selected) onSelect(pos)
            }

            override fun onStopTrackingTouch(slider: Slider) = Unit
        })
        vh.itemView.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onSelect(pos)
        }
        vh.remove.visibility = if (layers.size > 1 || name != null) View.VISIBLE else View.INVISIBLE
        vh.remove.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
        vh.handle.visibility = if (layers.size > 1) View.VISIBLE else View.INVISIBLE
        vh.handle.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(vh)
            false
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val handle: ImageView = v.findViewById(R.id.dragHandle)
        val name: TextView = v.findViewById(R.id.layerName)
        val slider: Slider = v.findViewById(R.id.layerStrength)
        val pct: TextView = v.findViewById(R.id.layerPct)
        val remove: ImageButton = v.findViewById(R.id.layerRemove)
    }
}
