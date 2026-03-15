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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    abstract fun screenTitle(): String
    abstract fun tiles(): List<Tile>

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tiles_info)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        val title = screenTitle()
        supportActionBar?.title = title
        toolbar.title = title
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // The screen title is shown in the top bar. 
        // The old small section header looked cramped, so we hide it to keep the layout clean.
        findViewById<TextView>(R.id.tvSection).visibility = View.GONE

        val container = findViewById<LinearLayout>(R.id.containerRows)
        val inflater = LayoutInflater.from(this)

        val list = tiles().filter { it.title.isNotBlank() && it.subtitle.isNotBlank() }

        list.forEachIndexed { idx, t ->
            val row = inflater.inflate(R.layout.item_info_tile, container, false)
            val root = row.findViewById<View>(R.id.root)
            val tvTitle = row.findViewById<TextView>(R.id.tvTitle)
            val tvSubtitle = row.findViewById<TextView>(R.id.tvSubtitle)
            val btnCopy = row.findViewById<ImageButton>(R.id.btnCopy)

            tvTitle.text = t.title
            tvSubtitle.text = t.subtitle

            root.isClickable = t.onClick != null
            root.setOnClickListener { t.onClick?.invoke() }

            root.setOnLongClickListener {
                copyToClipboard(t.copyValue)
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
                true
            }

            if (t.showCopyButton) {
                btnCopy.visibility = View.VISIBLE
                btnCopy.setOnClickListener {
                    copyToClipboard(t.copyValue)
                    Toast.makeText(this, t.copiedToast ?: getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }
            } else {
                btnCopy.visibility = View.GONE
            }

            container.addView(row)

            if (idx != list.lastIndex) {
                val divider = inflater.inflate(R.layout.item_info_divider, container, false)
                container.addView(divider)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Switchly", text))
    }
}
