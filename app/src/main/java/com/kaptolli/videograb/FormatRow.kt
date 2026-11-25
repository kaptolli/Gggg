package com.kaptolli.videograb

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kaptolli.videograb.databinding.ItemFormatBinding

data class FormatRow(
    val title: String,
    val subtitle: String,
    val selector: String,
    val audioOnly: Boolean,
    val audioFormat: String?
)

data class Preset(
    val label: String,
    val selector: String,
    val audioOnly: Boolean,
    val audioFormat: String?
)

class FormatAdapter(private val onClick: (FormatRow) -> Unit) : RecyclerView.Adapter<FormatAdapter.VH>() {

    private val items = ArrayList<FormatRow>()

    fun submit(list: List<FormatRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemFormatBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFormatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.b.tvRowTitle.text = row.title
        holder.b.tvRowSub.text = row.subtitle
        holder.b.root.setOnClickListener { onClick(row) }
    }
}
