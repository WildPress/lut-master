package app.lutapply

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

// One layer in the stack: which look it uses (index into the looks list, 0 = none) and how strongly.
class Layer(var look: Int, var strength: Float = 1f)

// The layer stack as a row of pills, applied left to right. Tap a pill to pick it, hold and drag
// to reorder. The selected pill shows an x to remove it.
class LayersAdapter(
    private val layers: MutableList<Layer>,
    private val nameOf: (Layer) -> String?,
    private val onSelect: (Int) -> Unit,
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

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
        override fun isLongPressDragEnabled() = layers.size > 1

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            val selectedLayer = layers[selected]
            layers.add(b, layers.removeAt(a))
            // Plain assignment: rebinding a pill mid-drag would cancel the drag. The highlight moves
            // with the pill's view anyway.
            selected = layers.indexOf(selectedLayer)
            notifyItemMoved(a, b)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        // The grade is rebuilt once, when the pill is dropped, not on every step of the drag.
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            if (moved) {
                moved = false
                onMoved()
            }
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.apply {
                alpha = 0.9f
                scaleX = 1.08f
                scaleY = 1.08f
            }
        }
    })

    private var moved = false

    // After adding or removing a layer every pill can change (numbers, x buttons, highlight).
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

    override fun onBindViewHolder(vh: VH, position: Int) {
        val layer = layers[position]
        val name = nameOf(layer)
        val isSelected = position == selected
        vh.itemView.isActivated = isSelected
        vh.number.text = (position + 1).toString()
        vh.number.visibility = if (layers.size > 1) View.VISIBLE else View.GONE
        vh.name.text = name ?: vh.itemView.context.getString(R.string.empty_layer)
        vh.name.alpha = if (name == null) 0.6f else 1f
        vh.itemView.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onSelect(pos)
        }
        vh.remove.visibility = if (isSelected && (layers.size > 1 || name != null)) View.VISIBLE else View.GONE
        vh.remove.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.layerNumber)
        val name: TextView = v.findViewById(R.id.layerName)
        val remove: ImageView = v.findViewById(R.id.layerRemove)
    }
}
