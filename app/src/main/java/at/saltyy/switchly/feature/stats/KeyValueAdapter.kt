package at.saltyy.switchly.feature.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.databinding.RowKeyValueBinding

class KeyValueAdapter : ListAdapter<KeyValueRow, KeyValueAdapter.VH>(DIFF) {

    fun submit(rows: List<KeyValueRow>) {
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = RowKeyValueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val b: RowKeyValueBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: KeyValueRow) {
            b.tvKey.text = row.key
            b.tvValue.text = row.value
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<KeyValueRow>() {
            override fun areItemsTheSame(oldItem: KeyValueRow, newItem: KeyValueRow): Boolean {
                return oldItem.key == newItem.key
            }

            override fun areContentsTheSame(oldItem: KeyValueRow, newItem: KeyValueRow): Boolean {
                return oldItem == newItem
            }
        }
    }
}
