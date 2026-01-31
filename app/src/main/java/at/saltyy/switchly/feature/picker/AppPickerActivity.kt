package at.saltyy.switchly.feature.picker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class AppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private var currentProfile: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val enabled = SwitchModeStore.isEnabled(this)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        val locked = enabled && requireNfc
        if (locked) {
            Toast.makeText(this, getString(R.string.toast_cannot_change_profile_while_locked), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        val etSearch = findViewById<TextInputEditText>(R.id.etSearch)
        val btnSave = findViewById<Button>(R.id.btnSave)

        rvApps.layoutManager = LinearLayoutManager(this)

        currentProfile = ProfileStore.getCurrent(this)

        val preselectedManaged: Set<String> = if (!currentProfile.isNullOrEmpty()) {
            ProfileStore.getBlockedForProfile(this, currentProfile!!).toSet()
        } else emptySet()

        adapter = AppListAdapter(
            allApps = emptyList(),
            preselectedManaged = preselectedManaged,
            currentProfileProvider = { currentProfile },
            onSetLimitClicked = { app -> showDailyLimitDialog(app) }
        )
        rvApps.adapter = adapter

        btnSave.backgroundTintList = AccentColor.getActiveColor(this)
        btnSave.setTextColor(ContextCompat.getColor(this, R.color.font_white))

        Thread {
            val apps = loadLaunchableApps(this)
            if (isFinishing || isDestroyed) return@Thread

            runOnUiThread {
                adapter = AppListAdapter(
                    allApps = apps,
                    preselectedManaged = preselectedManaged,
                    currentProfileProvider = { currentProfile },
                    onSetLimitClicked = { app -> showDailyLimitDialog(app) }
                )
                rvApps.adapter = adapter

                setupSearch(etSearch)
                setupSaveButton(btnSave)
            }
        }.start()
    }

    private fun loadLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcherIntent, 0)

        return resolved
            .mapNotNull { ri ->
                val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                val pkg = ai.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null

                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: pkg

                AppEntry(label = label, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun setupSearch(etSearch: TextInputEditText) {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString())
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                etSearch.clearFocus()
                true
            } else false
        }
    }

    private fun setupSaveButton(btnSave: Button) {
        btnSave.setOnClickListener {
            val profile = currentProfile
            if (profile.isNullOrEmpty()) {
                Toast.makeText(this, R.string.select_profile_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save "managed list" (apps that are either blocked OR have a limit)
            val managed = adapter.getManagedPackages()
            ProfileStore.setBlockedForProfile(this, profile, managed)

            BlockingRuntime.ensureRunning(this)
            finish()
        }
    }

    private fun showDailyLimitDialog(app: AppEntry) {
        val profile = currentProfile
        if (profile.isNullOrBlank()) {
            Toast.makeText(this, R.string.select_profile_first, Toast.LENGTH_SHORT).show()
            return
        }

        val presets = listOf(0, 3, 5, 10, 15, 30, 45, 60, 90, 120)
        val labels = presets.map { m ->
            if (m == 0){
                getString(R.string.no_limit)
            } else {
                resources.getQuantityString(R.plurals.minutes_format, m, m)
            }
        } + getString(R.string.custom_minutes)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_daily_limit_title, app.label))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    val chosen = presets[which]

                    UsageLimitStore.setLimitMinutes(this, profile, app.packageName, chosen)
                    if (chosen == 0) {
                        // clear usage so it doesn't feel "already exceeded"
                        UsageStore.setUsageMsToday(this, app.packageName, 0L)
                    }

                    BlockingRuntime.ensureRunning(this)
                    adapter.notifyPkgChanged(app.packageName)
                } else {
                    showCustomMinutesInput(app)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomMinutesInput(app: AppEntry) {
        val profile = currentProfile ?: return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.minutes_hint)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_minutes_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val m = input.text?.toString()?.trim()?.toIntOrNull()
                if (m == null || m < 0) {
                    Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                UsageLimitStore.setLimitMinutes(this, profile, app.packageName, m)
                if (m == 0) UsageStore.setUsageMsToday(this, app.packageName, 0L)

                BlockingRuntime.ensureRunning(this)
                adapter.notifyPkgChanged(app.packageName)
            }
            .show()
    }
}
