package app.lutapply

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

sealed class LooksRow {
    class Header(val title: String) : LooksRow()
    class Look(val index: Int, val name: String, val favKey: String?) : LooksRow()
}

class LooksAdapter(
    private val onSelected: (Int) -> Unit,
    private val onToggleFavourite: (String) -> Unit,
    private val isFavourite: (String) -> Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val thumbnails = HashMap<Int, Bitmap>()

    var rows: List<LooksRow> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var selectedIndex: Int = -1
        set(value) {
            val old = field
            field = value
            notifyLook(old)
            notifyLook(value)
        }

    fun isHeader(position: Int) = rows.getOrNull(position) is LooksRow.Header

    fun visibleLookIndices(): List<Int> = rows.mapNotNull { (it as? LooksRow.Look)?.index }

    fun setThumbnail(lookIndex: Int, bitmap: Bitmap) {
        thumbnails[lookIndex] = bitmap
        notifyLook(lookIndex)
    }

    fun clearThumbnails() {
        thumbnails.clear()
        notifyDataSetChanged()
    }

    private fun notifyLook(lookIndex: Int) {
        val pos = rows.indexOfFirst { it is LooksRow.Look && it.index == lookIndex }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun getItemViewType(position: Int) = if (isHeader(position)) 0 else 1

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
        else TileVH(inflater.inflate(R.layout.item_look_tile, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LooksRow.Header -> (holder as HeaderVH).title.text = row.title
            is LooksRow.Look -> {
                val vh = holder as TileVH
                val selected = row.index == selectedIndex
                vh.name.text = row.name
                vh.name.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                vh.card.isChecked = selected
                vh.thumb.setImageBitmap(thumbnails[row.index])
                vh.card.setOnClickListener { onSelected(row.index) }
                val key = row.favKey
                if (key == null) {
                    vh.star.visibility = View.GONE
                } else {
                    vh.star.visibility = View.VISIBLE
                    vh.star.setImageResource(if (isFavourite(key)) R.drawable.ic_star else R.drawable.ic_star_border)
                    vh.star.setOnClickListener {
                        onToggleFavourite(key)
                        val pos = vh.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                    }
                }
            }
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
    }

    private class TileVH(v: View) : RecyclerView.ViewHolder(v) {
        val card = v as MaterialCardView
        val thumb: ImageView = v.findViewById(R.id.thumbnail)
        val star: ImageButton = v.findViewById(R.id.star)
        val name: TextView = v.findViewById(R.id.lookName)
    }
}
