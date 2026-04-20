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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar

abstract class TilesInfoActivity : AppCompatActivity() {

    data class Tile(
        val title: String,
        val subtitle: String,
        val onClick: (() -> Unit)? = null,
        val copyValue: String = subtitle,
        val showCopyButton: Boolean = false,
        val copiedToast: String? = null
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
        renderTiles()
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

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

    private fun renderTiles() {
        rowsContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val visibleTiles = tiles().filter { it.title.isNotBlank() && it.subtitle.isNotBlank() }

        visibleTiles.forEachIndexed { index, tile ->
            rowsContainer.addView(createTileRow(inflater, tile))

            if (index != visibleTiles.lastIndex) {
                rowsContainer.addView(inflater.inflate(R.layout.item_info_divider, rowsContainer, false))
            }
        }
    }

    private fun createTileRow(inflater: LayoutInflater, tile: Tile): View {
        val row = inflater.inflate(R.layout.item_info_tile, rowsContainer, false)
        val root = row.findViewById<View>(R.id.root)
        val titleView = row.findViewById<TextView>(R.id.tvTitle)
        val subtitleView = row.findViewById<TextView>(R.id.tvSubtitle)
        val copyButton = row.findViewById<ImageButton>(R.id.btnCopy)

        titleView.text = tile.title
        subtitleView.text = tile.subtitle

        root.isClickable = tile.onClick != null
        root.setOnClickListener { tile.onClick?.invoke() }
        root.setOnLongClickListener {
            copyToClipboard(tile.copyValue)
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
            true
        }

        if (tile.showCopyButton) {
            copyButton.visibility = View.VISIBLE
            copyButton.setOnClickListener {
                copyToClipboard(tile.copyValue)
                Toast.makeText(
                    this,
                    tile.copiedToast ?: getString(R.string.copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            copyButton.visibility = View.GONE
        }

        return row
    }

    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Switchly", text))
    }
}
