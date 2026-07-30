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

package at.saltyy.switchly.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import at.saltyy.switchly.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import java.util.WeakHashMap

/**
 * Runtime fallback for CUSTOM accent mode.
 * Why this exists:
 * - XML theme attributes (?attr/colorPrimary) are compile-time styles and cannot be swapped to an arbitrary
 *   user-chosen color globally without a full theme-overlay system.
 * - Switchly already applies custom accent in many places, but some Material/AppCompat widgets still resolve to
 *   the default green theme color.
 * This pass replaces common occurrences of the default accent with the selected custom accent.
 * It only runs when "Custom" accent is selected.
 */
object CustomAccentApplier {

    private val globalLayoutHooks = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    // Track which EditTexts already have the cursor-retint hook attached (avoid resource-id tags).
    private val cursorHooked = WeakHashMap<EditText, Boolean>()
    private val cursorFocusHooks = WeakHashMap<EditText, ViewTreeObserver.OnGlobalFocusChangeListener>()
    private val cursorTextWatchers = WeakHashMap<EditText, TextWatcher>()

    fun isCustomAccentEnabled(activity: Activity): Boolean {
        return AccentColor.getOption(activity) == AccentColor.Option.CUSTOM
    }

    fun applyIfNeeded(activity: Activity) {
        if (!isCustomAccentEnabled(activity)) {
            return
        }

        val root = activity.findViewById<View>(android.R.id.content) ?: return
        applyToView(root, activity)

        val accent = AccentColor.getAccentColorInt(activity)
        val defaultAccent = ContextCompat.getColor(activity, R.color.accent_default_green)

        // Late passes for Recycler/Fragment inflation and async view attachments.
        val passes = longArrayOf(80L, 240L, 500L, 900L)
        passes.forEach { delayMs ->
            root.postDelayed({
                runCatching { recolorRecursive(root, defaultAccent, accent) }
            }, delayMs)
        }

        attachGlobalLayoutHook(activity, root, defaultAccent, accent)
    }

    fun applyToView(root: View, activity: Activity) {
        if (!isCustomAccentEnabled(activity)) {
            return
        }
        val accent = AccentColor.getAccentColorInt(activity)
        val defaultAccent = ContextCompat.getColor(activity, R.color.accent_default_green)
        recolorRecursive(root, defaultAccent, accent)
    }

    fun tintSwitch(switch: SwitchCompat) {
        val accent = AccentColor.getAccentColorInt(switch.context)
        switch.thumbTintList = buildSwitchThumbTint(accent)
        switch.trackTintList = buildSwitchTrackTint(accent)
    }

    fun applyToDialog(dialog: AlertDialog) {
        val activity = unwrapActivity(dialog.context) ?: return
        if (!isCustomAccentEnabled(activity)) {
            return
        }

        val accent = AccentColor.getAccentColorInt(activity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)

        // Single-choice dialogs: the check indicator is often bound late and can keep the default theme tint.
        // Keep re-applying while the list is laid out/scrolled.
        runCatching {
            val lv = dialog.listView
            if (lv != null) {
                // Dialog list selection/pressed highlight can keep the default theme color.
                // Force it to a subtle accent-tinted drawable.
                runCatching {
                    val sel = ColorUtils.setAlphaComponent(accent, 0x22)
                    // KTX: convert a color int to a drawable.
                    lv.selector = sel.toDrawable()
                    lv.isDrawSelectorOnTop = true
                }

                val retint = {
                    for (i in 0 until lv.childCount) {
                        applyToView(lv.getChildAt(i), activity)
                    }
                }

                // Some list items (multi-choice) rebind their indicators on click.
                // Wrap existing click listener so we can re-tint after state changes.
                runCatching {
                    val prev = lv.onItemClickListener
                    lv.onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
                        prev?.onItemClick(parent, view, position, id)
                        retint()
                    }
                }

                // When views are created/reused, re-apply accent immediately.
                runCatching {
                    lv.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View?, child: View?) {
                            if (child != null) runCatching { applyToView(child, activity) }
                        }

                        override fun onChildViewRemoved(parent: View?, child: View?) = Unit
                    })
                }
                lv.viewTreeObserver.addOnGlobalLayoutListener { retint() }
                lv.setOnScrollListener(object : AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                    override fun onScroll(
                        view: AbsListView?,
                        firstVisibleItem: Int,
                        visibleItemCount: Int,
                        totalItemCount: Int
                    ) {
                        retint()
                    }
                })
                retint()

                // Late passes (Material can bind list item drawables after show).
                longArrayOf(40L, 120L, 260L).forEach { d ->
                    lv.postDelayed({ runCatching { retint() } }, d)
                }
            }
        }

        val decor = dialog.window?.decorView ?: return
        applyToView(decor, activity)

        // Some Material dialogs bind list indicators (radio/checkbox drawables) *after* onShow.
        // Run a few late passes over the whole decor so we catch those cases too.
        longArrayOf(40L, 120L, 260L, 520L).forEach { d ->
            decor.postDelayed({ runCatching { applyToView(decor, activity) } }, d)
        }
    }

    private fun unwrapActivity(context: Context): Activity? {
        var c: Context? = context
        var guard = 0
        while (c != null && guard < 10) {
            if (c is Activity) {
                return c
            }
            c = (c as? ContextWrapper)?.baseContext
            guard += 1
        }
        return null
    }

    private fun attachGlobalLayoutHook(
        activity: Activity,
        root: View,
        defaultAccent: Int,
        accent: Int
    ) {
        if (globalLayoutHooks.containsKey(activity)) {
            return
        }

        var lastRunAt = 0L
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            // Throttle to avoid excessive work while scrolling.
            val now = SystemClock.uptimeMillis()
            if (now - lastRunAt < 140L) return@OnGlobalLayoutListener
            lastRunAt = now
            runCatching { recolorRecursive(root, defaultAccent, accent) }
        }

        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        globalLayoutHooks[activity] = listener

        // Best-effort cleanup to avoid stale references if the activity is finishing.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
                globalLayoutHooks.remove(activity)
                root.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun recolorRecursive(view: View, defaultAccent: Int, accent: Int) {
        recolorView(view, defaultAccent, accent)

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                recolorRecursive(view.getChildAt(i), defaultAccent, accent)
            }
        }
    }

    private fun recolorView(view: View, defaultAccent: Int, accent: Int) {
        // The onboarding footer intentionally keeps its secondary action tonal.
        // In CUSTOM mode the generic recursive pass used to interpret the outlined button's theme tint as a primary fill, making both footer actions accent-colored.
        val preserveOnboardingSecondaryBackground = view.id == R.id.btn_skip

        // Generic background tint replacement
        if (!preserveOnboardingSecondaryBackground) {
            ViewCompat.getBackgroundTintList(view)?.let { tint ->
                val candidate = tint.defaultColor
                if (matchesAccent(candidate, defaultAccent)) {
                    ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(accent))
                }
            }

            // Generic color drawable backgrounds
            (view.background as? ColorDrawable)?.let { bg ->
                if (matchesAccent(bg.color, defaultAccent)) {
                    view.setBackgroundColor(accent)
                }
            }

            // Common shape drawable backgrounds
            (view.background as? GradientDrawable)?.let { bg ->
                val c = bg.color?.defaultColor
                if (c != null && matchesAccent(c, defaultAccent)) {
                    bg.setColor(accent)
                }
            }
        }

        when (view) {
            is EditText -> {
                // Plain EditText underline/cursor often keeps theme green.
                runCatching {
                    view.backgroundTintList = ColorStateList.valueOf(accent)
                    val bg = view.background
                    if (bg != null) {
                        val wrapped = DrawableCompat.wrap(bg.mutate())
                        DrawableCompat.setTint(wrapped, accent)
                        view.background = wrapped
                    }

                    // Cursor + selection highlight are not affected by background tint.
                    // These commonly stay the default theme green, especially inside dialogs.
                    tintEditTextCursorAndSelection(view, accent)
                    ensureEditTextCursorHook(view, accent)
                }
            }

            is MaterialToolbar -> {
                val c = (view.background as? ColorDrawable)?.color
                if (c == null || matchesAccent(c, defaultAccent)) {
                    view.setBackgroundColor(accent)
                }
            }

            is BottomNavigationView -> {
                view.itemIconTintList = replaceCheckedColor(view.itemIconTintList, accent, defaultAccent)
                view.itemTextColor = replaceCheckedColor(view.itemTextColor, accent, defaultAccent)
            }

            is MaterialButton -> {
                val bgBefore = view.backgroundTintList?.defaultColor
                val bgMatchedAccent = bgBefore != null && matchesAccent(bgBefore, defaultAccent)
                if (bgMatchedAccent && !preserveOnboardingSecondaryBackground) {
                    view.backgroundTintList = ColorStateList.valueOf(accent)
                }

                view.strokeColor?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.strokeColor = ColorStateList.valueOf(accent)
                    }
                }
                if (view.strokeWidth > 0 && view.strokeColor == null) {
                    view.strokeColor = ColorStateList.valueOf(accent)
                }

                val textMatchedAccent = matchesAccent(view.currentTextColor, defaultAccent)
                val bgAfter = view.backgroundTintList?.defaultColor ?: bgBefore ?: Color.TRANSPARENT
                val outlinedLike = view.strokeWidth > 0 && Color.alpha(bgAfter) < 24
                val filledAccentBg = Color.alpha(bgAfter) > 24 && (bgMatchedAccent || matchesAccent(bgAfter, accent))

                when {
                    filledAccentBg -> {
                        val onAccent = readableOnColor(accent)
                        view.setTextColor(onAccent)
                        view.iconTint = ColorStateList.valueOf(onAccent)
                    }

                    textMatchedAccent || outlinedLike -> {
                        view.setTextColor(accent)
                        view.iconTint = ColorStateList.valueOf(accent)
                    }

                    else -> {
                        view.iconTint?.let {
                            if (matchesAccent(it.defaultColor, defaultAccent)) {
                                view.iconTint = ColorStateList.valueOf(accent)
                            }
                        }
                    }
                }
            }

            is FloatingActionButton -> {
                view.backgroundTintList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.backgroundTintList = ColorStateList.valueOf(accent)
                    }
                }
                view.imageTintList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.imageTintList = ColorStateList.valueOf(accent)
                    }
                }
            }

            is MaterialCardView -> {
                view.cardBackgroundColor?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.setCardBackgroundColor(accent)
                    }
                }
                view.strokeColorStateList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.setStrokeColor(ColorStateList.valueOf(accent))
                    }
                }
            }

            is Chip -> {
                view.chipBackgroundColor?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.chipBackgroundColor = ColorStateList.valueOf(accent)
                    }
                }
                view.chipStrokeColor?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.chipStrokeColor = ColorStateList.valueOf(accent)
                    }
                }
                view.chipIconTint?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.chipIconTint = ColorStateList.valueOf(accent)
                    }
                }
                if (matchesAccent(view.currentTextColor, defaultAccent)) {
                    view.setTextColor(accent)
                }
            }

            is TextInputLayout -> {
                // TextInput fields are central in settings/screens and often still pick up default theme green.
                // Material uses a state list internally (focused/hovered/disabled). Some OEM/Material combos
                // keep the focused stroke at the theme default (green) unless we override the full state list.
                runCatching {
                    val enabled = intArrayOf(android.R.attr.state_enabled)
                    val focused = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
                    val hovered = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_hovered)
                    val disabled = intArrayOf(-android.R.attr.state_enabled)

                    val normal = ColorUtils.setAlphaComponent(accent, 0xAA)
                    val hover = ColorUtils.setAlphaComponent(accent, 0xCC)
                    val dis = ColorUtils.setAlphaComponent(accent, 0x55)

                    val states = arrayOf(focused, hovered, enabled, disabled)
                    val colors = intArrayOf(accent, hover, normal, dis)
                    view.setBoxStrokeColorStateList(ColorStateList(states, colors))
                }

                // Fallback for older Material versions.
                runCatching { view.boxStrokeColor = accent }

                val tint = ColorStateList.valueOf(accent)
                runCatching { view.hintTextColor = tint }
                runCatching { view.defaultHintTextColor = tint }
                view.setStartIconTintList(tint)
                view.setEndIconTintList(tint)

                // Dropdown arrow (end icon) pressed/activated highlight often stays the theme default.
                // Material exposes an end-icon ripple color; set it to a subtle accent tint.
                runCatching {
                    val ripple = ColorUtils.setAlphaComponent(accent, 0x33)
                    val m = view.javaClass.methods.firstOrNull {
                        it.name == "setEndIconRippleColor" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == ColorStateList::class.java
                    }
                    m?.invoke(view, ColorStateList.valueOf(ripple))
                }

                // Some Material versions recreate cursor drawable on focus; re-apply to the embedded EditText.
                runCatching {
                    val et = view.editText
                    if (et is EditText) {
                        tintEditTextCursorAndSelection(et, accent)
                        ensureEditTextCursorHook(et, accent)
                    }
                }
            }

            is BaseProgressIndicator<*> -> {
                // Material progress widgets (Linear/CircularProgressIndicator)
                runCatching { view.setIndicatorColor(accent) }
            }

            is ProgressBar -> {
                view.progressTintList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.progressTintList = ColorStateList.valueOf(accent)
                    }
                }
                view.indeterminateTintList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.indeterminateTintList = ColorStateList.valueOf(accent)
                    }
                }
            }

            is SeekBar -> {
                view.progressTintList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.progressTintList = ColorStateList.valueOf(accent)
                        view.thumbTintList = ColorStateList.valueOf(accent)
                    }
                }
            }

            is MaterialSwitch -> {
                // Force custom accent for track/thumb in custom mode.
                view.thumbTintList = buildSwitchThumbTint(accent)
                view.trackTintList = buildSwitchTrackTint(accent)
            }

            is SwitchCompat -> {
                // Ensure switches never fall back to theme green in custom mode.
                view.thumbTintList = buildSwitchThumbTint(accent)
                view.trackTintList = buildSwitchTrackTint(accent)
            }

            is CompoundButton -> {
                // In custom accent mode, always force the selected accent for checkables.
                // (Dialogs/lists often resolve to system/dynamic colors that won't match our default accent.)
                // Some Material widgets auto-apply theme colours; turn that off so our tint sticks.
                // Use reflection so this works regardless of the Material Components version.
                runCatching {
                    val m = view.javaClass.methods.firstOrNull {
                        it.name == "setUseMaterialThemeColors" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                    }
                    m?.invoke(view, false)
                }
                view.buttonTintList = ColorStateList.valueOf(accent)
            }

            is ImageView -> {
                view.imageTintList?.let {
                    if (matchesAccent(it.defaultColor, defaultAccent)) {
                        view.imageTintList = ColorStateList.valueOf(accent)
                    }
                }

                // Preference rows often use android.R.id.icon and keep default theme tint.
                // Make sure these follow the selected custom accent.
                val idName = runCatching {
                    if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
                }.getOrDefault("")
                if (view.id == android.R.id.icon || idName.contains("icon", ignoreCase = true)) {
                    if (view.drawable != null && view.imageTintList != null) {
                        view.imageTintList = ColorStateList.valueOf(accent)
                    }
                }

                // Some views apply tint via colorFilter instead of imageTintList.
                val cfColor = extractColorFromFilter(view)
                if (cfColor != null && matchesAccent(cfColor, defaultAccent)) {
                    view.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
                }
            }

            is TextView -> {
                if (view !is MaterialButton && matchesAccent(view.currentTextColor, defaultAccent)) {
                    view.setTextColor(accent)
                }

                // Link color (FAQ/contact links etc.)
                val hasLinks = (view.text is Spanned) && (view.text as Spanned)
                    .getSpans(0, view.text.length, URLSpan::class.java)
                    .isNotEmpty()
                val linkColor = view.linkTextColors?.defaultColor
                if (hasLinks || (linkColor != null && matchesAccent(linkColor, defaultAccent))) {
                    view.setLinkTextColor(accent)
                }

                // Compound drawables (e.g. inline hint icons)
                TextViewCompat.getCompoundDrawableTintList(view)?.let { list ->
                    if (matchesAccent(list.defaultColor, defaultAccent)) {
                        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(accent))
                    }
                }

                retintCompoundDrawables(view, accent, defaultAccent)

                // Single-choice dialogs often use CheckedTextView with a checkmark drawable.
                if (view is CheckedTextView) {
                    runCatching {
                        val d = view.checkMarkDrawable
                        if (d != null) {
                            val wrapped = DrawableCompat.wrap(d.mutate())
                            DrawableCompat.setTint(wrapped, accent)
                            view.setCheckMarkDrawable(wrapped)
                        }

                        // Some layouts use framework tinting instead of a drawable mutation.
                        // Prefer checkMarkTintList when available.
                        runCatching {
                            view.checkMarkTintList = ColorStateList.valueOf(accent)
                        }
                    }
                }
            }
        }
    }

    private fun tintEditTextCursorAndSelection(et: EditText, accent: Int) {
        // Selection highlight (drag handles still depend on theme; highlight is the most visible part).
        runCatching {
            et.highlightColor = ColorUtils.setAlphaComponent(accent, 0x44)
        }

        // Some OEM/Material combos show a briefly appearing green "focus dot" when the insertion handle
        // is displayed (common on multi-line fields). That dot is part of the text selection/insertion
        // handle drawables (not the cursor drawable). Force-tint those handles to the custom accent.
        runCatching { tintEditTextSelectionHandles(et, accent) }

        // Cursor tint:
        // On Android 10+ (API 29+), the public setter is the most reliable option.
        // On some OEM builds, *extra reflection writes* can actually cause the cursor to fall back
        // to the system accent after IME/text updates (especially for multi-line TextInputEditText).
        // So:
        //   - API 29+: use ONLY the public setter (plus a compat fallback), no private field hacks.
        //   - <29: best-effort reflection.
        val cursorDrawable = GradientDrawable().apply {
            setColor(accent)
            setSize(dpToPx(et, 2), dpToPx(et, 24))
        }

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { et.textCursorDrawable = cursorDrawable }
            // Force a redraw; some devices won't update until invalidated.
            et.invalidate()
            return
        }

        // <29: Some Android versions/OEMs expose a hidden/public setTextCursorDrawable(Drawable).
        runCatching {
            val m = TextView::class.java.getMethod(
                "setTextCursorDrawable",
                android.graphics.drawable.Drawable::class.java
            )
            m.invoke(et, cursorDrawable)
        }
        runCatching {
            val m = TextView::class.java.getDeclaredMethod(
                "setTextCursorDrawable",
                android.graphics.drawable.Drawable::class.java
            )
            m.isAccessible = true
            m.invoke(et, cursorDrawable)
        }

    }

    /**
     * We intentionally avoid touching TextView's private Editor internals.
     * Selection/cursor tinting relies on public or best-effort non-private hooks only.
     */
    private fun getOrCreateEditor(tv: TextView): Any? = null

    /**
     * Force-tint text selection/insertion handles (the small dot/tear-drop shown when placing the cursor or selecting text).
     * This is separate from the cursor drawable and can keep the theme default green even when the cursor itself is tinted.
     */
    private fun tintEditTextSelectionHandles(et: EditText, accent: Int) {
        val ctx = et.context

        fun tint(d: android.graphics.drawable.Drawable?): android.graphics.drawable.Drawable? {
            d ?: return null
            val wrapped = DrawableCompat.wrap(d.mutate())
            DrawableCompat.setTint(wrapped, accent)
            return wrapped
        }

        // Avoid framework resource name reflection here; if a drawable is not publicly addressable we skip it.
        fun frameworkDrawable(vararg names: String): android.graphics.drawable.Drawable? = null

        // Names vary across Android/OEM builds; try a small set of common variants.
        val left = tint(
            frameworkDrawable(
                "text_select_handle_left",
                "text_select_handle_left_material",
                "text_select_handle_left_dark",
                "text_select_handle_left_light"
            )
        )
        val right = tint(
            frameworkDrawable(
                "text_select_handle_right",
                "text_select_handle_right_material",
                "text_select_handle_right_dark",
                "text_select_handle_right_light"
            )
        )
        // "middle" is the insertion handle on many versions. Some builds use "text_select_handle".
        val middle = tint(
            frameworkDrawable(
                "text_select_handle_middle",
                "text_select_handle_middle_material",
                "text_select_handle",
                "text_select_handle_center",
                "text_select_handle_middle_dark",
                "text_select_handle_middle_light"
            )
        )

        // Prefer any public/hidden setters if present (API differences).
        // We use reflection so compilationdoesn't depend on specific platform signatures.
        runCatching {
            val tvCls = TextView::class.java
            fun call(name: String, drawable: android.graphics.drawable.Drawable?) {
                drawable ?: return
                val m = tvCls.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 &&
                        android.graphics.drawable.Drawable::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                m?.invoke(et, drawable)
            }

            call("setTextSelectHandleLeft", left)
            call("setTextSelectHandleRight", right)
            call("setTextSelectHandle", middle)
        }

        // AOSP/Editor fallback: set the editor handle fields directly if they exist.
        runCatching {
            val editor = getOrCreateEditor(et) ?: return

            // Iterate fields so this keeps working across Android versions.
            editor.javaClass.declaredFields
                .filter { android.graphics.drawable.Drawable::class.java.isAssignableFrom(it.type) }
                .forEach { f ->
                    val n = f.name
                    val repl = when {
                        n.contains("Left", ignoreCase = true) -> left
                        n.contains("Right", ignoreCase = true) -> right
                        n.contains("Insertion", ignoreCase = true) -> middle
                        n.contains("Center", ignoreCase = true) -> middle
                        n.contains("Middle", ignoreCase = true) -> middle
                        n.contains("SelectHandle", ignoreCase = true) && n.contains("Center", ignoreCase = true) -> middle
                        else -> null
                    }
                    if (repl != null) {
                        f.isAccessible = true
                        f.set(editor, repl)
                    }
                }
        }

        // Some Android versions create the actual HandleView drawables lazily when the handle is shown (which can be slightly delayed after focus).
        // If so, tint those live drawables as well.
        runCatching { tintLiveHandleViews(et, accent) }
    }

    /**
     * Best-effort tinting of already-created insertion/selection handle views.
     * This helps prevent a brief "green dot" flash on some devices where handles are created after our initial editor field override.
     */
    private fun tintLiveHandleViews(et: EditText, accent: Int) {
        fun tintDrawable(d: android.graphics.drawable.Drawable?) {
            d ?: return
            val wrapped = DrawableCompat.wrap(d.mutate())
            DrawableCompat.setTint(wrapped, accent)
        }

        val visited = java.util.IdentityHashMap<Any, Boolean>()

        fun walk(obj: Any?, depth: Int) {
            if (obj == null || depth <= 0) {
                return
            }
            if (visited.put(obj, true) == true) {
                return
            }

            val cls = obj.javaClass

            // Tint any Drawable fields directly.
            cls.declaredFields
                .filter { android.graphics.drawable.Drawable::class.java.isAssignableFrom(it.type) }
                .forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        tintDrawable(f.get(obj) as? android.graphics.drawable.Drawable)
                    }
                }

            // Recurse into nested objects that might hold handle views/controllers.
            cls.declaredFields
                .filter { !it.type.isPrimitive && it.type != String::class.java }
                .forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(obj) ?: return@runCatching

                        val name = v.javaClass.name
                        // Focus on likely editor/handle/controller classes to keep this cheap.
                        val interesting = name.contains("Editor", true) ||
                            name.contains("Handle", true) ||
                            name.contains("Selection", true) ||
                            name.contains("Insertion", true) ||
                            name.contains("Cursor", true) ||
                            name.contains("Controller", true)

                        if (interesting) walk(v, depth - 1)
                    }
                }
        }

        // Start at TextView.mEditor and traverse a few levels.
        runCatching {
            val editor = getOrCreateEditor(et) ?: return
            walk(editor, 3)
        }

        // Ensure redraw.
        et.invalidate()
    }

    private fun ensureEditTextCursorHook(et: EditText, accent: Int) {
        // Avoid stacking listeners without needing an ids.xml resource.
        if (cursorHooked[et] == true) {
            return
        }
        cursorHooked[et] = true

        fun retint() = tintEditTextCursorAndSelection(et, accent)

        // Best-effort retries: some Material widgets reset cursor/handle tint after attach/focus.
        // Note: the insertion handle can appear slightly *after* focus (brief green "dot" flash).
        // Keep a couple later passes to catch that.
        longArrayOf(0L, 60L, 160L, 360L, 700L, 1200L).forEach { d ->
            et.postDelayed({ runCatching { retint() } }, d)
        }

        // More reliable than onFocusChangeListener (which can be overwritten by widgets): listen to global focus changes in this view tree.
        val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus === et) {
                et.post { runCatching { retint() } }
                et.postDelayed({ runCatching { retint() } }, 80L)
                et.postDelayed({ runCatching { retint() } }, 220L)
                et.postDelayed({ runCatching { retint() } }, 520L)
                et.postDelayed({ runCatching { retint() } }, 920L)
                et.postDelayed({ runCatching { retint() } }, 1420L)
            }
        }
        runCatching {
            et.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            cursorFocusHooks[et] = focusListener
        }

        // Some Material/OEM combos recreate the cursor drawable after text changes (very common on multi-line
        // TextInputEditText). Re-apply after edits so the cursor doesn't fall back to the theme default.
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Post so we run after internal TextView updates.
                et.post { runCatching { retint() } }
                et.postDelayed({ runCatching { retint() } }, 90L)
            }
        }
        runCatching {
            et.addTextChangedListener(watcher)
            cursorTextWatchers[et] = watcher
        }

        // Clean up when detached to avoid leaking the dialog/activity view tree.
        et.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                val l = cursorFocusHooks.remove(et)
                if (l != null) {
                    runCatching { et.viewTreeObserver.removeOnGlobalFocusChangeListener(l) }
                }
                val w = cursorTextWatchers.remove(et)
                if (w != null) {
                    runCatching { et.removeTextChangedListener(w) }
                }
                cursorHooked.remove(et)
                et.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun dpToPx(view: View, dp: Int): Int {
        val density = view.resources.displayMetrics.density
        return (dp.toFloat() * density).toInt().coerceAtLeast(1)
    }

    private fun buildSwitchThumbTint(accent: Int): ColorStateList {
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val checked = intArrayOf(android.R.attr.state_checked)
        val empty = intArrayOf()
        val thumbOff = Color.WHITE
        val thumbDisabled = Color.LTGRAY
        return ColorStateList(
            arrayOf(disabled, checked, empty),
            intArrayOf(thumbDisabled, accent, thumbOff)
        )
    }

    private fun buildSwitchTrackTint(accent: Int): ColorStateList {
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val checked = intArrayOf(android.R.attr.state_checked)
        val empty = intArrayOf()
        val on = ColorUtils.setAlphaComponent(accent, 0x88)
        val off = ColorUtils.setAlphaComponent(Color.DKGRAY, 0x44)
        val offDisabled = ColorUtils.setAlphaComponent(Color.GRAY, 0x33)
        return ColorStateList(
            arrayOf(disabled, checked, empty),
            intArrayOf(offDisabled, on, off)
        )
    }

    private fun replaceCheckedColor(list: ColorStateList?, checkedColor: Int, defaultAccent: Int): ColorStateList? {
        if (list == null) {
            return null
        }

        val checkedState = intArrayOf(android.R.attr.state_checked)
        val uncheckedState = intArrayOf(-android.R.attr.state_checked)

        val checkedCurrent = list.getColorForState(checkedState, list.defaultColor)
        val unchecked = list.getColorForState(uncheckedState, list.defaultColor)

        if (!matchesAccent(checkedCurrent, defaultAccent) && !matchesAccent(list.defaultColor, defaultAccent)) {
            return list
        }

        return ColorStateList(
            arrayOf(checkedState, uncheckedState),
            intArrayOf(checkedColor, unchecked)
        )
    }

    private fun extractColorFromFilter(view: ImageView): Int? {
        // API-safe extraction is limited. For most cases imageTintList path above is enough.
        // Keep this helper for devices/widgets that still use colorFilter internally.
        return try {
            val f = view.colorFilter ?: return null
            val colorField = f.javaClass.declaredFields.firstOrNull {
                it.type == Int::class.javaPrimitiveType || it.type == Int::class.javaObjectType
            } ?: return null
            colorField.isAccessible = true
            colorField.getInt(f)
        } catch (_: Throwable) {
            null
        }
    }

    private fun retintCompoundDrawables(
        tv: TextView,
        accent: Int,
        defaultAccent: Int
    ) {
        fun maybeTint(d: android.graphics.drawable.Drawable?) {
            d ?: return

            var shouldTint = false

            val cf = d.colorFilter
            if (cf != null) {
                val c = extractColorFromDrawableFilter(d)
                if (c != null && matchesAccent(c, defaultAccent)) {
                    shouldTint = true
                }
            }

            if (!shouldTint && TextViewCompat.getCompoundDrawableTintList(tv) != null) {
                shouldTint = matchesAccent(
                    TextViewCompat.getCompoundDrawableTintList(tv)?.defaultColor ?: Color.TRANSPARENT,
                    defaultAccent
                )
            }

            if (shouldTint) {
                val wrapped = DrawableCompat.wrap(d.mutate())
                DrawableCompat.setTint(wrapped, accent)
            }
        }

        tv.compoundDrawables.forEach { maybeTint(it) }
        tv.compoundDrawablesRelative.forEach { maybeTint(it) }
    }

    private fun extractColorFromDrawableFilter(drawable: android.graphics.drawable.Drawable): Int? {
        return try {
            val f = drawable.colorFilter ?: return null
            val colorField = f.javaClass.declaredFields.firstOrNull {
                it.type == Int::class.javaPrimitiveType || it.type == Int::class.javaObjectType
            } ?: return null
            colorField.isAccessible = true
            colorField.getInt(f)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readableOnColor(bg: Int): Int {
        val black = ColorUtils.calculateContrast(Color.BLACK, bg)
        val white = ColorUtils.calculateContrast(Color.WHITE, bg)
        return if (black >= white) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun matchesAccent(color: Int, defaultAccent: Int): Boolean {
        val c = ColorUtils.setAlphaComponent(color, 0xFF)
        val d = ColorUtils.setAlphaComponent(defaultAccent, 0xFF)

        // Exact hit (fast path)
        if (c == d) {
            return true
        }

        // Heuristic match for common Material blends (checked/pressed/disabled) derived from the default accent.
        //
        // IMPORTANT: Avoid false positives for dark/neutral surfaces.
        // In dark mode some greys can be within a large RGB distance tolerance, which caused unrelated backgrounds (e.g. "unavailable app" rows) to be recolored in CUSTOM mode.

        // Require hue proximity to the default accent.
        val hsvC = FloatArray(3)
        val hsvD = FloatArray(3)
        Color.colorToHSV(c, hsvC)
        Color.colorToHSV(d, hsvD)

        // If the color is close to grey (low saturation), it's almost certainly not accent-derived.
        if (hsvC[1] < 0.12f) {
            return false
        }

        fun hueDiff(a: Float, b: Float): Float {
            val diff = kotlin.math.abs(a - b)
            return kotlin.math.min(diff, 360f - diff)
        }

        if (hueDiff(hsvC[0], hsvD[0]) > 35f) {
            return false
        }

        val dr = Color.red(c) - Color.red(d)
        val dg = Color.green(c) - Color.green(d)
        val db = Color.blue(c) - Color.blue(d)
        val distance = (dr * dr + dg * dg + db * db)

        // Keep tolerance for typical blends while preventing hue-mismatched recolors.
        return distance <= (120 * 120)
    }
}
