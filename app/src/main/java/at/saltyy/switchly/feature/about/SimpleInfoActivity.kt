package at.saltyy.switchly.feature.about

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R

abstract class SimpleInfoActivity : AppCompatActivity() {

    data class InfoItem(val label: String, val value: String)

    abstract fun titleRes(): Int
    abstract fun buildItems(): List<InfoItem>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_info)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.tvSectionTitle).setText(titleRes())

        val container = findViewById<LinearLayout>(R.id.containerItems)
        val inflater = LayoutInflater.from(this)
        val items = buildItems().filter { it.label.isNotBlank() && it.value.isNotBlank() }

        items.forEachIndexed { idx, item ->
            val row = inflater.inflate(R.layout.item_info_row, container, false)
            row.findViewById<TextView>(R.id.tvLabel).text = item.label
            row.findViewById<TextView>(R.id.tvValue).text = item.value
            container.addView(row)

            if (idx != items.lastIndex) {
                val divider = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                        bottomMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setBackgroundColor(0x14000000) // subtle divider
                }
                container.addView(divider)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
