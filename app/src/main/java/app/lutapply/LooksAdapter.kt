package app.lutapply

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

sealed class LooksRow {
    class Header(val title: String) : LooksRow()
    class Look(val index: Int, val name: String, var thumbnail: Bitmap? = null) : LooksRow()
}

class LooksAdapter(
    private val onSelected: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<LooksRow> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var selectedIndex: Int = -1
        set(value) {
            val old = field
            field = value
            notifyItemChangedIfLook(old)
            notifyItemChangedIfLook(value)
        }

    private fun notifyItemChangedIfLook(lookIndex: Int) {
        if (lookIndex < 0) return
        val pos = rows.indexOfFirst { it is LooksRow.Look && it.index == lookIndex }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun updateThumbnail(lookIndex: Int, bitmap: Bitmap) {
        val pos = rows.indexOfFirst { it is LooksRow.Look && it.index == lookIndex }
        if (pos >= 0) {
            (rows[pos] as LooksRow.Look).thumbnail = bitmap
            notifyItemChanged(pos)
        }
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is LooksRow.Header -> 0
        is LooksRow.Look -> 1
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
            else -> LookVH(inflater.inflate(R.layout.item_look, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LooksRow.Header -> (holder as HeaderVH).title.text = row.title
            is LooksRow.Look -> {
                val vh = holder as LookVH
                vh.name.text = row.name
                val selected = row.index == selectedIndex
                vh.card.isChecked = selected
                vh.check.visibility = if (selected) View.VISIBLE else View.GONE
                if (row.thumbnail != null) {
                    vh.thumb.setImageBitmap(row.thumbnail)
                    vh.thumb.visibility = View.VISIBLE
                } else {
                    vh.thumb.visibility = View.GONE
                }
                vh.card.setOnClickListener {
                    onSelected(row.index)
                }
            }
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
    }

    private class LookVH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v as MaterialCardView
        val thumb: ImageView = v.findViewById(R.id.thumbnail)
        val name: TextView = v.findViewById(R.id.lookName)
        val check: ImageView = v.findViewById(R.id.checkIcon)
    }
}
