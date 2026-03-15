package at.saltyy.switchly.feature.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

private data class ReleaseNote(
    val version: String,
    val date: String,
    val lines: List<String>,
)

private enum class ChangeKind {
    ADDED,
    CHANGED,
    IMPROVED,
    FIXED,
    REMOVED,
    OTHER
}

class WhatsNewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_changelog)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Keep status bar neutral (like other screens)
        window.statusBarColor = getColor(android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        renderReleaseNotes()
    }

    private fun renderReleaseNotes() {
        val container = findViewById<LinearLayout>(R.id.releaseNotesContainer)
        container.removeAllViews()

        val notes = loadReleaseNotes()
        val inflater = LayoutInflater.from(this)

        notes.forEachIndexed { index, note ->
            val item = inflater.inflate(R.layout.item_release_note, container, false)
            val tvVersion = item.findViewById<TextView>(R.id.tvVersion)
            val tvDate = item.findViewById<TextView>(R.id.tvDate)
            val tvBody = item.findViewById<TextView>(R.id.tvBody)
            val btnToggle = item.findViewById<MaterialButton>(R.id.btnToggleBody)

            val previewItems = if (index == 0) 4 else 3
            var expanded = false

            tvVersion.text = if (index == 0) {
                getString(R.string.changelog_latest_fmt, note.version)
            } else {
                note.version
            }

            tvDate.text = if (note.date.isNotBlank()) {
                resources.getQuantityString(R.plurals.changelog_date_count_fmt, note.lines.size, note.date, note.lines.size)
            } else {
                resources.getQuantityString(R.plurals.changelog_count_only_fmt, note.lines.size, note.lines.size)
            }

            fun bindBody() {
                tvBody.text = if (expanded) {
                    buildExpandedBody(note)
                } else {
                    buildCollapsedBody(note, previewItems)
                }

                val expandable = note.lines.size > previewItems
                btnToggle.visibility = if (expandable) View.VISIBLE else View.GONE
                if (expandable) {
                    btnToggle.text = if (expanded) {
                        getString(R.string.changelog_show_less)
                    } else {
                        getString(R.string.changelog_show_full_fmt, note.lines.size)
                    }
                }
            }

            bindBody()
            btnToggle.setOnClickListener {
                expanded = !expanded
                bindBody()
            }

            container.addView(item)
        }
    }

    private fun buildCollapsedBody(note: ReleaseNote, previewItems: Int): String {
        val highlights = pickHighlights(note.lines, previewItems)
        val sb = StringBuilder()
        sb.append(getString(R.string.changelog_highlights)).append('\n')
        highlights.forEach { sb.append("• ").append(shortenLine(it, 108)).append('\n') }

        val remaining = (note.lines.size - highlights.size).coerceAtLeast(0)
        if (remaining > 0) {
            sb.append('\n').append(resources.getQuantityString(R.plurals.changelog_more_changes_fmt, remaining, remaining))
        }
        return sb.toString().trim()
    }

    private fun buildExpandedBody(note: ReleaseNote): String {
        val grouped = linkedMapOf(
            ChangeKind.ADDED to mutableListOf<String>(),
            ChangeKind.CHANGED to mutableListOf<String>(),
            ChangeKind.IMPROVED to mutableListOf<String>(),
            ChangeKind.FIXED to mutableListOf<String>(),
            ChangeKind.REMOVED to mutableListOf<String>(),
            ChangeKind.OTHER to mutableListOf<String>()
        )

        note.lines.forEach { raw ->
            val line = sanitizeLine(raw)
            grouped[classifyLine(line)]?.add(line)
        }

        val sb = StringBuilder()
        grouped.forEach { (kind, lines) ->
            if (lines.isEmpty()) return@forEach
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(kindTitle(kind)).append('\n')
            lines.forEach { line -> sb.append("• ").append(shortenLine(line, 180)).append('\n') }
        }

        return sb.toString().trim()
    }

    private fun pickHighlights(lines: List<String>, limit: Int): List<String> {
        if (lines.isEmpty() || limit <= 0) return emptyList()

        val buckets = linkedMapOf(
            ChangeKind.FIXED to mutableListOf<String>(),
            ChangeKind.IMPROVED to mutableListOf<String>(),
            ChangeKind.ADDED to mutableListOf<String>(),
            ChangeKind.CHANGED to mutableListOf<String>(),
            ChangeKind.REMOVED to mutableListOf<String>(),
            ChangeKind.OTHER to mutableListOf<String>()
        )

        lines.forEach { raw ->
            val clean = sanitizeLine(raw)
            buckets[classifyLine(clean)]?.add(clean)
        }

        val out = mutableListOf<String>()

        // First pass: one from each bucket (keeps preview diverse)
        buckets.values.forEach { list ->
            if (list.isNotEmpty() && out.size < limit) out += list.first()
        }

        // Second pass: fill from original order
        if (out.size < limit) {
            lines.map(::sanitizeLine).forEach { line ->
                if (out.size >= limit) return@forEach
                if (!out.contains(line)) out += line
            }
        }

        return out
    }

    private fun classifyLine(line: String): ChangeKind {
        val l = line.lowercase()
        return when {
            l.startsWith("fix") || l.startsWith("fixed") || l.contains(" bug") || l.contains("crash") -> ChangeKind.FIXED
            l.startsWith("improv") || l.contains("improved") || l.contains("reliability") || l.contains("stability") || l.contains("harden") -> ChangeKind.IMPROVED
            l.startsWith("added") || l.startsWith("add ") || l.contains("new ") -> ChangeKind.ADDED
            l.startsWith("changed") || l.startsWith("updated") || l.startsWith("renamed") || l.startsWith("moved") || l.startsWith("reworked") || l.contains(" now ") -> ChangeKind.CHANGED
            l.startsWith("removed") || l.startsWith("deprecated") || l.contains("hidden") -> ChangeKind.REMOVED
            else -> ChangeKind.OTHER
        }
    }

    private fun kindTitle(kind: ChangeKind): String = when (kind) {
        ChangeKind.ADDED -> getString(R.string.changelog_section_added)
        ChangeKind.CHANGED -> getString(R.string.changelog_section_changed)
        ChangeKind.IMPROVED -> getString(R.string.changelog_section_improved)
        ChangeKind.FIXED -> getString(R.string.changelog_section_fixed)
        ChangeKind.REMOVED -> getString(R.string.changelog_section_removed)
        ChangeKind.OTHER -> getString(R.string.changelog_section_other)
    }

    private fun sanitizeLine(line: String): String {
        return line
            .replace("\u201c", "\"")
            .replace("\u201d", "\"")
            .replace("\u2019", "'")
            .replace("\\s+".toRegex(), " ")
            .removePrefix("•")
            .trim()
    }

    private fun shortenLine(line: String, maxChars: Int): String {
        if (line.length <= maxChars) return line
        val cut = line.take(maxChars)
        val safe = cut.substringBeforeLast(' ')
        val base = if (safe.length >= (maxChars * 0.65f).toInt()) safe else cut
        return base.trimEnd('.', ',', ';', ':') + "…"
    }

    // Loads release notes from res/raw/changelog.json (fallback: empty list).
    private fun loadReleaseNotes(): List<ReleaseNote> {
        return runCatching {
            val json = resources.openRawResource(R.raw.changelog)
                .bufferedReader()
                .use { it.readText() }

            val obj = JSONObject(json)
            val arr = obj.getJSONArray("releases")
            val out = ArrayList<ReleaseNote>(arr.length())

            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val version = r.optString("version").trim()
                val date = r.optString("date").trim()

                val lines = mutableListOf<String>()
                val bodyArr = r.optJSONArray("body")
                if (bodyArr != null) {
                    for (j in 0 until bodyArr.length()) {
                        val line = bodyArr.optString(j).trim()
                        if (line.isNotBlank()) lines += line.removePrefix("• ").trim()
                    }
                } else {
                    r.optString("body")
                        .split("\n")
                        .map { it.trim().removePrefix("• ").trim() }
                        .filterTo(lines) { it.isNotBlank() }
                }

                if (version.isNotBlank() && lines.isNotEmpty()) {
                    out += ReleaseNote(
                        version = version,
                        date = date,
                        lines = lines
                    )
                }
            }

            out
        }.getOrElse { emptyList() }
    }
}
