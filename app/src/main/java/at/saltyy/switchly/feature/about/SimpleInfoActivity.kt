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
