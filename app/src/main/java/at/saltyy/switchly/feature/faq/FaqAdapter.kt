package at.saltyy.switchly.feature.faq

import android.content.res.ColorStateList
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import com.google.android.material.color.MaterialColors

class FaqAdapter(
    private val items: List<FaqListItem>
) : RecyclerView.Adapter<FaqAdapter.VH>() {

    private var expanded = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_faq, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {

        private val tvHeader: TextView = v.findViewById(R.id.tvHeader)
        private val questionRow: View = v.findViewById(R.id.questionRow)
        private val tvQuestion: TextView = v.findViewById(R.id.tvQuestion)
        private val tvAnswer: TextView = v.findViewById(R.id.tvAnswer)
        private val ivArrow: ImageView = v.findViewById(R.id.ivArrow)
        private val ivIcon: ImageView = v.findViewById(R.id.ivIcon)

        fun bind(item: FaqListItem, pos: Int) {
            val ctx = itemView.context
            val accent = AccentColor.getAccentColorInt(ctx)
            val default = tvQuestion.currentTextColor

            when (item) {
                is FaqListItem.Header -> {
                    tvHeader.isVisible = true
                    tvHeader.text = item.title
                    tvHeader.setTextColor(accent)

                    questionRow.isVisible = false
                    tvAnswer.isVisible = false
                }

                is FaqListItem.Item -> {
                    tvHeader.isVisible = false
                    questionRow.isVisible = true

                    tvQuestion.text = item.question
                    tvAnswer.text = item.answer
                    tvAnswer.movementMethod = LinkMovementMethod.getInstance()

                    if (item.iconRes != null) {
                        ivIcon.isVisible = true
                        ivIcon.setImageResource(item.iconRes)
                    } else {
                        ivIcon.isVisible = false
                    }

                    val isOpen = pos == expanded

                    ivIcon.setColorFilter(default)
                    ivArrow.setColorFilter(default)

                    if (isOpen) {
                        ivIcon.setColorFilter(accent)
                        ivArrow.setColorFilter(accent)
                    }

                    tvAnswer.isVisible = isOpen
                    ivArrow.rotation = if (isOpen) 180f else 0f

                    questionRow.setOnClickListener {
                        val old = expanded
                        expanded = if (isOpen) RecyclerView.NO_POSITION else pos

                        if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
                        notifyItemChanged(pos)
                    }
                }
            }
        }
    }
}