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

package at.saltyy.switchly.feature.usage

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.IgnoredUsageAppsStore
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.statistics.UsageInsightsAppCatalog
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.SegmentedToggleUi
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.PackageLaunchIntentCompat
import at.saltyy.switchly.util.PackageManagerApiCompat
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/**
 * Manages separate visibility filters for Usage & Insights and app-selection screens.
 * Changes in either tab remain staged until Save is pressed.
 */
class IgnoredUsageAppsActivity : AppCompatActivity() {
    private enum class Section {
        USAGE_INSIGHTS,
        APP_PICKERS,
    }

    private lateinit var adapter: IgnoredUsageAppsAdapter
    private lateinit var countView: TextView
    private var currentSection = Section.USAGE_INSIGHTS
    private var usageSelection: Set<String> = emptySet()
    private var appPickerSelection: Set<String> = emptySet()
    private var suggestedPackages: Set<String> = emptySet()
    private var usagePackages: Set<String> = emptySet()
    private var appPickerPackages: Set<String> = emptySet()
    private var loadedItems: List<IgnoredUsageAppItem> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ignored_usage_apps)
        CustomAccentApplier.applyIfNeeded(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.keyboard_arrow_left_24)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        toolbar.menu.add(Menu.NONE, MENU_INFO, 0, R.string.ignored_usage_apps_info).apply {
            setIcon(R.drawable.info_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(Menu.NONE, MENU_CLEAR, 1, R.string.ignored_usage_apps_clear).apply {
            setIcon(R.drawable.delete_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INFO -> {
                    showInfoDialog()
                    true
                }
                MENU_CLEAR -> {
                    adapter.replaceSelection(emptySet())
                    true
                }
                else -> false
            }
        }
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        usageSelection = IgnoredUsageAppsStore.getIgnoredPackages(this)
        appPickerSelection = IgnoredUsageAppsStore.getAppPickerHiddenPackages(this)
        currentSection = if (intent.getBooleanExtra(EXTRA_SHOW_APP_PICKERS, false)) {
            Section.APP_PICKERS
        } else {
            Section.USAGE_INSIGHTS
        }
        countView = findViewById(R.id.tvIgnoredCount)
        adapter = IgnoredUsageAppsAdapter(usageSelection) { selection ->
            when (currentSection) {
                Section.USAGE_INSIGHTS -> usageSelection = selection
                Section.APP_PICKERS -> appPickerSelection = selection
            }
            updateCount(selection.size)
        }

        findViewById<RecyclerView>(R.id.rvIgnoredUsageApps).apply {
            layoutManager = LinearLayoutManager(this@IgnoredUsageAppsActivity)
            adapter = this@IgnoredUsageAppsActivity.adapter
            setHasFixedSize(true)
        }

        findViewById<TextInputEditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.setQuery(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        setupSectionToggle()

        findViewById<MaterialButton>(R.id.btnSaveIgnoredApps).setOnClickListener {
            IgnoredUsageAppsStore.setIgnoredPackages(this, usageSelection)
            IgnoredUsageAppsStore.setAppPickerHiddenPackages(this, appPickerSelection)
            Toast.makeText(this, R.string.ignored_usage_apps_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        updateCount(currentSelection().size)
        loadApps(usageSelection + appPickerSelection)
    }

    private fun setupSectionToggle() {
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggleHiddenAppsSection)
        val usageButton = findViewById<MaterialButton>(R.id.btnHiddenUsageInsights)
        val pickerButton = findViewById<MaterialButton>(R.id.btnHiddenAppPickers)
        val selectedId = if (currentSection == Section.APP_PICKERS) {
            R.id.btnHiddenAppPickers
        } else {
            R.id.btnHiddenUsageInsights
        }
        toggle.check(selectedId)
        SegmentedToggleUi.apply(this, listOf(usageButton, pickerButton), selectedId)
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentSection = if (checkedId == R.id.btnHiddenAppPickers) {
                Section.APP_PICKERS
            } else {
                Section.USAGE_INSIGHTS
            }
            SegmentedToggleUi.apply(this, listOf(usageButton, pickerButton), checkedId)
            refreshSection()
        }
    }

    private fun refreshSection() {
        val selection = currentSelection()
        adapter.replaceSelection(selection)
        val visiblePackages = when (currentSection) {
            Section.USAGE_INSIGHTS -> usagePackages
            Section.APP_PICKERS -> appPickerPackages
        }
        val items = loadedItems
            .asSequence()
            .filter { it.packageName in visiblePackages }
            .map { item ->
                item.copy(
                    suggested = item.packageName in suggestedPackages,
                )
            }
            .sortedWith(
                compareByDescending<IgnoredUsageAppItem> { it.packageName in selection }
                    .thenByDescending { it.suggested }
                    .thenBy { it.label.lowercase(Locale.getDefault()) },
            )
            .toList()
        adapter.setItems(items)
        updateCount(selection.size)
    }

    private fun currentSelection(): Set<String> {
        return when (currentSection) {
            Section.USAGE_INSIGHTS -> usageSelection
            Section.APP_PICKERS -> appPickerSelection
        }
    }

    private fun showInfoDialog() {
        val message = when (currentSection) {
            Section.USAGE_INSIGHTS -> R.string.hidden_apps_usage_description
            Section.APP_PICKERS -> R.string.hidden_apps_picker_description
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ignored_usage_apps_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun loadApps(selected: Set<String>) {
        val appContext = applicationContext
        Thread {
            suggestedPackages = IgnoredUsageAppsStore.suggestedPackages(appContext)
            val launchablePackages = queryLaunchablePackages(appContext)
            usagePackages = buildSet {
                addAll(launchablePackages)
                addAll(UsageStore.getUsageMsMapOverall(appContext).keys)
                addAll(suggestedPackages)
                addAll(usageSelection)
            }
            appPickerPackages = buildSet {
                addAll(launchablePackages.filterNot { it == appContext.packageName })
                addAll(
                    InAppRuleStore.supportedPackages()
                        .filter { PackageLaunchIntentCompat.isLaunchable(appContext, it) }
                )
                addAll(appPickerSelection)
            }
            val packages = usagePackages + appPickerPackages + selected

            loadedItems = packages
                .filterNot(UsageInsightsAppCatalog::shouldAlwaysHide)
                .mapNotNull { packageName ->
                    val appInfo = applicationInfo(appContext, packageName) ?: return@mapNotNull null
                    IgnoredUsageAppItem(
                        packageName = packageName,
                        label = appContext.packageManager.getApplicationLabel(appInfo).toString(),
                        icon = runCatching { appContext.packageManager.getApplicationIcon(packageName) }.getOrNull(),
                        suggested = false,
                    )
                }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) refreshSection()
            }
        }.start()
    }

    private fun queryLaunchablePackages(context: Context): Set<String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            PackageManagerApiCompat.queryIntentActivities(
                packageManager = context.packageManager,
                intent = launcherIntent,
            ).mapNotNullTo(linkedSetOf()) { it.activityInfo?.packageName }
        }.getOrDefault(emptySet())
    }

    private fun applicationInfo(context: Context, packageName: String): ApplicationInfo? {
        return runCatching {
            PackageManagerApiCompat.getApplicationInfo(
                packageManager = context.packageManager,
                packageName = packageName,
            )
        }.getOrNull()
    }

    private fun updateCount(count: Int) {
        countView.text = resources.getQuantityString(
            R.plurals.ignored_usage_apps_count,
            count,
            count,
        )
    }

    companion object {
        private const val MENU_INFO = 1
        private const val MENU_CLEAR = 2
        private const val EXTRA_SHOW_APP_PICKERS = "extra_show_app_pickers"

        fun intent(context: Context, showAppPickers: Boolean = false): Intent =
            Intent(context, IgnoredUsageAppsActivity::class.java)
                .putExtra(EXTRA_SHOW_APP_PICKERS, showAppPickers)
    }
}
