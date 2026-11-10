package app.lutapply

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

sealed class LooksRow {
    class Header(val title: String) : LooksRow()
    class Look(val index: Int, val name: String, val favKey: String?) : LooksRow()
    // Carousel layout: one category's looks in a single row that scrolls sideways.
    class Strip(val key: String, val looks: List<Look>) : LooksRow()
}

// The looks list. In the grid layout its rows are headers and tiles; in the carousel layout they
// are headers and strips, each strip a sideways list of tiles. Thumbnails, the selected look and
// favourites live here and every strip reads them, so updates reach whichever tile shows a look.
class LooksAdapter(
    private val onSelected: (Int) -> Unit,
    private val onToggleFavourite: (String) -> Unit,
    private val isFavourite: (String) -> Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val thumbnails = HashMap<Int, Bitmap>()
    private val strips = HashSet<StripAdapter>()
    // Sideways scroll position per strip, so rebuilding the list doesn't send every row back to
    // its start.
    private val stripScroll = HashMap<String, android.os.Parcelable?>()
    private val stripPool = RecyclerView.RecycledViewPool()

    // Width of a tile inside a strip, in pixels. Set by the activity to match the grid's tiles.
    var stripTileWidth = 0

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

    fun visibleLookIndices(): List<Int> = rows.flatMap { r ->
        when (r) {
            is LooksRow.Look -> listOf(r.index)
            is LooksRow.Strip -> r.looks.map { it.index }
            is LooksRow.Header -> emptyList()
        }
    }

    fun setThumbnail(lookIndex: Int, bitmap: Bitmap) {
        thumbnails[lookIndex] = bitmap
        notifyLook(lookIndex)
    }

    fun clearThumbnails() {
        thumbnails.clear()
        notifyDataSetChanged()
        strips.forEach { it.notifyDataSetChanged() }
    }

    // A look can appear more than once (its category and Favourites), so update every copy.
    private fun notifyLook(lookIndex: Int) {
        if (lookIndex < 0) return
        rows.forEachIndexed { pos, r -> if (r is LooksRow.Look && r.index == lookIndex) notifyItemChanged(pos) }
        strips.forEach { it.notifyLook(lookIndex) }
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is LooksRow.Header -> TYPE_HEADER
        is LooksRow.Look -> TYPE_TILE
        is LooksRow.Strip -> TYPE_STRIP
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_TILE -> TileVH(inflater.inflate(R.layout.item_look_tile, parent, false))
            else -> StripVH(RecyclerView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                setRecycledViewPool(stripPool)
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                itemAnimator = null
            })
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LooksRow.Header -> (holder as HeaderVH).title.text = row.title
            is LooksRow.Look -> bindTile(holder as TileVH, row)
            is LooksRow.Strip -> {
                val vh = holder as StripVH
                vh.adapter?.let { saveStrip(vh) }
                val strip = StripAdapter(row)
                vh.key = row.key
                vh.adapter = strip
                vh.list.adapter = strip
                strips.add(strip)
                val saved = stripScroll[row.key]
                if (saved != null) vh.list.layoutManager?.onRestoreInstanceState(saved)
                else vh.list.scrollToPosition(0)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is StripVH) {
            saveStrip(holder)
            holder.adapter?.let { strips.remove(it) }
            holder.adapter = null
        }
    }

    private fun saveStrip(vh: StripVH) {
        val key = vh.key ?: return
        stripScroll[key] = vh.list.layoutManager?.onSaveInstanceState()
    }

    // Strips start at their beginning again when the filter or layout changes.
    fun resetStripScroll() = stripScroll.clear()

    private fun bindTile(vh: TileVH, row: LooksRow.Look) {
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
            vh.star.setOnClickListener { onToggleFavourite(key) }
        }
    }

    // Updates every copy of a look's star after it's toggled.
    fun refreshFavourite(lookIndex: Int) = notifyLook(lookIndex)

    private inner class StripAdapter(val strip: LooksRow.Strip) : RecyclerView.Adapter<TileVH>() {
        override fun getItemCount() = strip.looks.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_look_tile, parent, false)
            if (stripTileWidth > 0) v.layoutParams = v.layoutParams.apply { width = stripTileWidth }
            return TileVH(v)
        }

        override fun onBindViewHolder(holder: TileVH, position: Int) = bindTile(holder, strip.looks[position])

        fun notifyLook(lookIndex: Int) {
            strip.looks.forEachIndexed { i, l -> if (l.index == lookIndex) notifyItemChanged(i) }
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

    private class StripVH(val list: RecyclerView) : RecyclerView.ViewHolder(list) {
        var adapter: StripAdapter? = null
        var key: String? = null
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TILE = 1
        const val TYPE_STRIP = 2
    }
}
