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

package at.saltyy.switchly.feature.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

abstract class TilesInfoActivity : AppCompatActivity() {

    data class Tile(
        val title: String,
        val subtitle: String,
        val sectionTitle: String? = null,
        val onClick: (() -> Unit)? = null,
        val onLongClick: (() -> Boolean)? = null,
        val enableLongPressCopy: Boolean = true,
        val copyValue: String = subtitle,
        val showCopyButton: Boolean = false,
        val showOpenButton: Boolean = false,
        @param:DrawableRes @field:DrawableRes val iconRes: Int? = null,
        @param:DrawableRes @field:DrawableRes val actionIconRes: Int? = null,
        val copiedToast: String? = null,
        @param:ColorRes @field:ColorRes val subtitleColorRes: Int? = null,
        val subtitleAlpha: Float? = null
    )

    private lateinit var toolbar: MaterialToolbar
    private lateinit var rowsContainer: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    abstract fun screenTitle(): String
    abstract fun tiles(): List<Tile>

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tiles_info)

        setupToolbar()
        setupContent()
        refreshTiles()
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        val toolbarIconColor = readableToolbarIconColor()
        toolbar.navigationIcon?.mutate()?.setTint(toolbarIconColor)
        toolbar.setTitleTextColor(toolbarIconColor)

        val title = screenTitle()
        supportActionBar?.title = title
        toolbar.title = title
    }

    private fun setupContent() {
        rowsContainer = findViewById(R.id.containerRows)

        // The screen title is shown in the top bar.
        // The old small section header looked cramped, so we hide it to keep the layout clean.
        findViewById<TextView>(R.id.tvSection).visibility = View.GONE
    }

    protected fun refreshTiles() {
        rowsContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val visibleTiles = tiles().filter { it.title.isNotBlank() && it.subtitle.isNotBlank() }

        visibleTiles
            .fold(mutableListOf<Pair<String?, MutableList<Tile>>>()) { groups, tile ->
                val section = tile.sectionTitle?.takeIf { it.isNotBlank() }
                if (groups.lastOrNull()?.first == section) {
                    groups.last().second += tile
                } else {
                    groups += section to mutableListOf(tile)
                }
                groups
            }
            .forEachIndexed { groupIndex, (section, groupTiles) ->
                section?.let { rowsContainer.addView(createSectionTitle(it, groupIndex > 0)) }

                val card = MaterialCardView(this).apply {
                    radius = dp(14).toFloat()
                    cardElevation = dp(1).toFloat()
                    useCompatPadding = true
                    strokeWidth = dp(1)
                    strokeColor = ContextCompat.getColor(this@TilesInfoActivity, R.color.switchly_card_stroke)
                    setCardBackgroundColor(ContextCompat.getColor(this@TilesInfoActivity, R.color.switchly_card_bg))
                }
                val groupContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }

                groupTiles.forEachIndexed { index, tile ->
                    groupContainer.addView(createTileRow(inflater, tile))

                    if (index != groupTiles.lastIndex) {
                        groupContainer.addView(inflater.inflate(R.layout.item_info_divider, groupContainer, false))
                    }
                }

                card.addView(groupContainer)
                rowsContainer.addView(card)
            }
    }

    private fun createSectionTitle(title: String, hasTopMargin: Boolean): TextView =
        TextView(this).apply {
            text = title
            setTextColor(AccentColor.getAccentColorInt(this@TilesInfoActivity))
            textSize = 12.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            isAllCaps = true
            setPadding(dp(4), if (hasTopMargin) dp(14) else dp(2), dp(4), dp(5))
        }

    private fun createTileRow(inflater: LayoutInflater, tile: Tile): View {
        val row = inflater.inflate(R.layout.item_info_tile, rowsContainer, false)
        val root = row.findViewById<View>(R.id.root)
        val iconView = row.findViewById<ImageView>(R.id.ivIcon)
        val titleView = row.findViewById<TextView>(R.id.tvTitle)
        val subtitleView = row.findViewById<TextView>(R.id.tvSubtitle)
        val copyButton = row.findViewById<ImageButton>(R.id.btnCopy)
        val clickAction = tile.onClick

        val accentTint = ColorStateList.valueOf(AccentColor.getAccentColorInt(this))
        tile.iconRes?.let { iconRes ->
            iconView.visibility = View.VISIBLE
            iconView.setImageResource(iconRes)
            ImageViewCompat.setImageTintList(iconView, accentTint)
        } ?: run {
            iconView.visibility = View.GONE
        }

        titleView.text = tile.title
        subtitleView.text = tile.subtitle
        tile.subtitleColorRes?.let { subtitleView.setTextColor(ContextCompat.getColor(this, it)) }
        subtitleView.alpha = tile.subtitleAlpha ?: if (tile.subtitleColorRes != null) 1f else 0.72f

        root.isClickable = clickAction != null || tile.onLongClick != null
        root.setOnClickListener { clickAction?.invoke() }
        root.setOnLongClickListener {
            when {
                tile.onLongClick != null -> tile.onLongClick.invoke()
                tile.enableLongPressCopy -> {
                    copyToClipboard(tile.copyValue)
                    Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        when {
            tile.showCopyButton -> {
                copyButton.visibility = View.VISIBLE
                copyButton.setImageResource(tile.actionIconRes ?: R.drawable.content_copy_24)
                copyButton.contentDescription = getString(R.string.action_copy)
                copyButton.alpha = 0.72f
                ImageViewCompat.setImageTintList(copyButton, accentTint)
                copyButton.setOnClickListener {
                    copyToClipboard(tile.copyValue)
                    Toast.makeText(
                        this,
                        tile.copiedToast ?: getString(R.string.copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            tile.showOpenButton && clickAction != null -> {
                copyButton.visibility = View.VISIBLE
                copyButton.setImageResource(tile.actionIconRes ?: R.drawable.open_in_new_24)
                copyButton.contentDescription = tile.title
                copyButton.alpha = 0.7f
                ImageViewCompat.setImageTintList(copyButton, accentTint)
                copyButton.setOnClickListener { clickAction.invoke() }
            }
            else -> {
                copyButton.visibility = View.GONE
                copyButton.setOnClickListener(null)
            }
        }

        return row
    }

    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Switchly", text))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun readableToolbarIconColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }
}
