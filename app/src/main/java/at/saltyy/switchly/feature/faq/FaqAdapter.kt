/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
import com.google.android.material.card.MaterialCardView

class FaqAdapter(
    private val items: List<FaqListItem>,
    private val onFolderClick: (FaqListItem.Folder) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var expanded = RecyclerView.NO_POSITION

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is FaqListItem.Folder -> VIEW_TYPE_FOLDER
        else -> VIEW_TYPE_FAQ
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOLDER) {
            FolderVH(inflater.inflate(R.layout.row_faq_folder, parent, false))
        } else {
            FaqVH(inflater.inflate(R.layout.row_faq, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FaqListItem.Folder -> (holder as FolderVH).bind(item)
            else -> (holder as FaqVH).bind(item, position)
        }
    }

    inner class FolderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: MaterialCardView = v.findViewById(R.id.cardFaqFolder)
        private val iconCard: MaterialCardView = v.findViewById(R.id.cardFaqFolderIcon)
        private val icon: ImageView = v.findViewById(R.id.ivFaqFolderIcon)
        private val title: TextView = v.findViewById(R.id.tvFaqFolderTitle)
        private val subtitle: TextView = v.findViewById(R.id.tvFaqFolderSubtitle)
        private val count: TextView = v.findViewById(R.id.tvFaqFolderCount)

        fun bind(item: FaqListItem.Folder) {
            val ctx = itemView.context
            val accent = AccentColor.getAccentColorInt(ctx)
            title.text = item.title
            subtitle.text = item.subtitle
            count.text = ctx.resources.getQuantityString(
                R.plurals.faq_category_articles_count,
                item.articleCount,
                item.articleCount
            )
            icon.setImageResource(item.iconRes ?: R.drawable.folder_24)
            icon.imageTintList = ColorStateList.valueOf(accent)
            val softAccent = (accent and 0x00FFFFFF) or (0x1E shl 24)
            iconCard.setCardBackgroundColor(softAccent)
            card.setOnClickListener { onFolderClick(item) }
        }
    }

    inner class FaqVH(v: View) : RecyclerView.ViewHolder(v) {

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
            val mutedAccent = (accent and 0x00FFFFFF) or (0xCC shl 24)

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

                    ivIcon.setColorFilter(mutedAccent)
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

                is FaqListItem.Folder -> Unit
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_FAQ = 0
        private const val VIEW_TYPE_FOLDER = 1
    }
}
