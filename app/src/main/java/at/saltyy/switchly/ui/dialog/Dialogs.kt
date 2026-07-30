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

package at.saltyy.switchly.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Central place for all dialogs so they are consistent and never fall back to OEM green.
object Dialogs {
    fun builder(ctx: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(ctx)
}

data class SwitchlyDialogOption(
    val title: CharSequence,
    val summary: CharSequence? = null,
    @param:DrawableRes val iconRes: Int? = null,
    val iconDrawable: Drawable? = null,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true
)


fun AlertDialog.styleSwitchlyDestructivePositiveButton() {
    styleSwitchlyDialogButtons()
    val error = Color.rgb(186, 26, 26)
    val onError = if (ColorUtils.calculateLuminance(error) > 0.5) Color.BLACK else Color.WHITE
    getButton(AlertDialog.BUTTON_POSITIVE)?.let { b ->
        b.setTextColor(onError)
        b.backgroundTintList = ColorStateList.valueOf(error)
    }
}

fun MaterialAlertDialogBuilder.showDestructiveAccented(): AlertDialog {
    val dlg = this.create()
    dlg.setOnShowListener { dlg.styleSwitchlyDestructivePositiveButton() }
    dlg.show()
    return dlg
}

fun AlertDialog.Builder.showDestructiveAccented(): AlertDialog {
    val dlg = this.create()
    dlg.setOnShowListener { dlg.styleSwitchlyDestructivePositiveButton() }
    dlg.show()
    return dlg
}

// Show a Material dialog and enforce custom accent colors at runtime (CUSTOM accent mode), including list check indicators (radio/checkbox) which often fall back to OEM green.
fun MaterialAlertDialogBuilder.showAccented(): AlertDialog {
    val dlg = this.create()
    dlg.setOnShowListener {
        dlg.styleSwitchlyDialogButtons()
    }
    dlg.show()
    return dlg
}

// Show an AppCompat dialog and enforce custom accent colors at runtime (CUSTOM accent mode).
fun AlertDialog.Builder.showAccented(): AlertDialog {
    val dlg = this.create()
    dlg.setOnShowListener {
        dlg.styleSwitchlyDialogButtons()
    }
    dlg.show()
    return dlg
}

/**
 * One shared Switchly option-dialog style.
 * Used for action menus, mode pickers, sort/filter pickers, profile actions, QR/barcode choices, etc.
 * Existing-value choices wait for the filled confirmation action; command menus still runimmediately.
 * No OEM green radios/checkmarks; selection uses accent border/background only.
 */
private fun Context.showSwitchlyOptionDialogInternal(
    title: CharSequence,
    subtitle: CharSequence?,
    options: List<SwitchlyDialogOption>,
    onCancelled: (() -> Unit)? = null,
    compact: Boolean = false,
    showCancelButton: Boolean = true,
    widthFraction: Float = 0.94f,
    confirmSelection: Boolean = options.any { it.selected },
    onSelected: (index: Int) -> Unit
): AlertDialog {
    val accent = AccentColor.getAccentColorInt(this)
    val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT)
    val surfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, surface)
    val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    val error = Color.rgb(186, 26, 26)
    fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    val outerPad = 2
    val rowHPad = 12
    val rowVPad = if (compact) 8 else 10
    val rowRadius = if (compact) 14 else 18
    val titleSp = if (compact) 15.5f else 16.25f
    val summarySp = if (compact) 13f else 13.5f
    val iconSize = if (compact) 22 else 24
    val drawableIconSize = 26
    val iconGap = 12

    val scroll = ScrollView(this).apply {
        isFillViewport = false
        clipToPadding = false
        setPadding(dp(outerPad), 0, dp(outerPad), dp(if (compact) 8 else 6))
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setPadding(0, 0, 0, dp(if (compact) 3 else 4))
    }
    scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val titleView = TextView(this).apply {
        text = title
        setTextColor(onSurface)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), 0, dp(2), dp(if (subtitle.isNullOrBlank()) 14 else 4))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setPadding(dp(16), dp(20), dp(16), dp(0))
        addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        subtitle?.takeIf { it.isNotBlank() }?.let { subtitleText ->
            addView(TextView(this@showSwitchlyOptionDialogInternal).apply {
                text = subtitleText
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xB0))
                textSize = 13.5f
                setPadding(dp(2), 0, dp(2), dp(14))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    lateinit var dialog: AlertDialog
    var selectedIndex = options.indexOfFirst { it.selected }
    val optionCards = mutableListOf<MaterialCardView>()
    val optionTitles = mutableListOf<TextView>()

    fun updateSelection(index: Int) {
        val option = options[index]
        val optionAccent = if (option.destructive) error else accent
        val selected = index == selectedIndex
        optionCards[index].strokeWidth = dp(if (selected) 2 else 0)
        optionCards[index].strokeColor = if (selected) optionAccent else Color.TRANSPARENT
        optionCards[index].setCardBackgroundColor(
            if (selected) ColorUtils.setAlphaComponent(optionAccent, 0x14) else surfaceVariant
        )
        optionTitles[index].typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    options.forEachIndexed { index, option ->
        val optionAccent = if (option.destructive) error else accent
        val selected = index == selectedIndex
        val card = MaterialCardView(this).apply {
            radius = dp(rowRadius).toFloat()
            cardElevation = 0f
            useCompatPadding = false
            isClickable = option.enabled
            isFocusable = option.enabled
            isEnabled = option.enabled
            alpha = if (option.enabled) 1f else 0.55f
            strokeWidth = dp(if (selected) 2 else 0)
            strokeColor = if (selected) optionAccent else Color.TRANSPARENT
            setCardBackgroundColor(if (selected) ColorUtils.setAlphaComponent(optionAccent, 0x14) else surfaceVariant)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(rowHPad), dp(rowVPad), dp(rowHPad), dp(rowVPad))
        }

        when {
            option.iconDrawable != null -> {
                val icon = ImageView(this).apply {
                    setImageDrawable(option.iconDrawable)
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(drawableIconSize), dp(drawableIconSize)).apply { marginEnd = dp(iconGap) })
            }
            option.iconRes != null -> {
                val icon = ImageView(this).apply {
                    setImageResource(option.iconRes)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(optionAccent))
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { marginEnd = dp(iconGap) })
            }
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(this).apply {
            text = option.title
            setTextColor(if (option.destructive) error else onSurface)
            textSize = titleSp
            if (selected) typeface = Typeface.DEFAULT_BOLD
        }
        optionCards += card
        optionTitles += titleView
        textColumn.addView(titleView)

        option.summary?.takeIf { it.isNotBlank() }?.let { summaryText ->
            val summaryView = TextView(this).apply {
                text = summaryText
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xB0))
                textSize = summarySp
                setPadding(0, dp(if (compact) 2 else 3), 0, 0)
            }
            textColumn.addView(summaryView)
        }

        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        card.setOnClickListener {
            if (!option.enabled) return@setOnClickListener
            if (confirmSelection) {
                selectedIndex = index
                options.indices.forEach(::updateSelection)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            } else {
                dialog.dismiss()
                onSelected(index)
            }
        }
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(if (compact) 5 else 6) })
    }

    val builder = MaterialAlertDialogBuilder(this)
        .setView(content)
        .setOnCancelListener { onCancelled?.invoke() }
    if (confirmSelection) {
        builder.setPositiveButton(R.string.ok) { _, _ ->
            if (selectedIndex in options.indices) onSelected(selectedIndex)
        }
    }
    if (showCancelButton || confirmSelection) {
        builder.setNegativeButton(R.string.cancel) { _, _ -> onCancelled?.invoke() }
    }
    dialog = builder.create()

    dialog.setOnShowListener {
        dialog.styleSwitchlyDialogButtons()
        if (confirmSelection) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selectedIndex in options.indices
        }
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * widthFraction).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
    return dialog
}

fun Context.showSwitchlyOptionDialog(
    title: CharSequence,
    options: List<SwitchlyDialogOption>,
    onCancelled: (() -> Unit)? = null,
    compact: Boolean = false,
    showCancelButton: Boolean = true,
    widthFraction: Float = 0.94f,
    confirmSelection: Boolean = options.any { it.selected },
    onSelected: (index: Int) -> Unit
): AlertDialog = showSwitchlyOptionDialogInternal(
    title,
    null,
    options,
    onCancelled,
    compact,
    showCancelButton,
    widthFraction,
    confirmSelection,
    onSelected
)

fun Context.showSwitchlyOptionDialog(
    title: CharSequence,
    subtitle: CharSequence?,
    options: List<SwitchlyDialogOption>,
    onCancelled: (() -> Unit)? = null,
    compact: Boolean = false,
    showCancelButton: Boolean = true,
    widthFraction: Float = 0.94f,
    confirmSelection: Boolean = options.any { it.selected },
    onSelected: (index: Int) -> Unit
): AlertDialog = showSwitchlyOptionDialogInternal(
    title,
    subtitle,
    options,
    onCancelled,
    compact,
    showCancelButton,
    widthFraction,
    confirmSelection,
    onSelected
)


fun Context.showSwitchlyMultiChoiceDialog(
    title: CharSequence,
    options: List<SwitchlyDialogOption>,
    checked: BooleanArray,
    @StringRes positiveTextRes: Int = android.R.string.ok,
    compact: Boolean = false,
    widthFraction: Float = 0.94f,
    onConfirmed: (checked: BooleanArray) -> Unit
): AlertDialog {
    val accent = AccentColor.getAccentColorInt(this)
    val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT)
    val surfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, surface)
    val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    val error = Color.rgb(186, 26, 26)
    fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    val outerPad = 2
    val rowHPad = 12
    val rowVPad = if (compact) 8 else 10
    val rowRadius = if (compact) 14 else 18
    val titleSp = if (compact) 15.5f else 16.25f
    val summarySp = if (compact) 13f else 13.5f
    val iconSize = if (compact) 22 else 24
    val drawableIconSize = 26
    val iconGap = 12

    val scroll = ScrollView(this).apply {
        isFillViewport = false
        setPadding(dp(outerPad), 0, dp(outerPad), dp(if (compact) 0 else 2))
    }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val rowStates = checked.copyOf(options.size)
    val cards = mutableListOf<MaterialCardView>()
    val boxes = mutableListOf<CheckBox>()
    lateinit var dialog: AlertDialog
    lateinit var headerTitleView: TextView

    fun selectedCountLabel(): CharSequence {
        val count = rowStates.count { it }
        if (count <= 0) {
            return title
        }
        val countLabel = resources.getQuantityString(
            R.plurals.dialog_selected_count,
            count,
            count
        )
        return "$title · $countLabel"
    }

    fun updateRow(index: Int) {
        val card = cards.getOrNull(index) ?: return
        val box = boxes.getOrNull(index) ?: return
        val option = options[index]
        val optionAccent = if (option.destructive) error else accent
        val isChecked = rowStates[index]
        box.isChecked = isChecked
        card.strokeWidth = dp(if (isChecked) 2 else 0)
        card.strokeColor = if (isChecked) optionAccent else Color.TRANSPARENT
        card.setCardBackgroundColor(if (isChecked) ColorUtils.setAlphaComponent(optionAccent, 0x14) else surfaceVariant)
    }

    options.forEachIndexed { index, option ->
        val optionAccent = if (option.destructive) error else accent
        val card = MaterialCardView(this).apply {
            radius = dp(rowRadius).toFloat()
            cardElevation = 0f
            useCompatPadding = false
            isClickable = option.enabled
            isFocusable = option.enabled
            isEnabled = option.enabled
            alpha = if (option.enabled) 1f else 0.55f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(rowHPad), dp(rowVPad), dp(rowHPad), dp(rowVPad))
        }
        when {
            option.iconDrawable != null -> {
                val icon = ImageView(this).apply {
                    setImageDrawable(option.iconDrawable)
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(drawableIconSize), dp(drawableIconSize)).apply { marginEnd = dp(iconGap) })
            }
            option.iconRes != null -> {
                val icon = ImageView(this).apply {
                    setImageResource(option.iconRes)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(optionAccent))
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { marginEnd = dp(iconGap) })
            }
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowTitleView = TextView(this).apply {
            text = option.title
            setTextColor(if (option.destructive) error else onSurface)
            textSize = titleSp
        }
        textColumn.addView(rowTitleView)
        option.summary?.takeIf { it.isNotBlank() }?.let { summaryText ->
            val summaryView = TextView(this).apply {
                text = summaryText
                setTextColor(ColorUtils.setAlphaComponent(onSurface, 0xB0))
                textSize = summarySp
                setPadding(0, dp(if (compact) 2 else 3), 0, 0)
            }
            textColumn.addView(summaryView)
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val box = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
            CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(optionAccent))
        }
        row.addView(box)
        boxes += box
        cards += card
        card.addView(row)
        card.setOnClickListener {
            if (!option.enabled) return@setOnClickListener
            rowStates[index] = !rowStates[index]
            updateRow(index)
            headerTitleView.text = selectedCountLabel()
        }
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(if (compact) 5 else 6) })
        updateRow(index)
    }

    headerTitleView = TextView(this).apply {
        text = selectedCountLabel()
        setTextColor(onSurface)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), 0, dp(2), dp(14))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setPadding(dp(16), dp(20), dp(16), dp(0))
        addView(headerTitleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    dialog = MaterialAlertDialogBuilder(this)
        .setView(content)
        .setPositiveButton(positiveTextRes) { _, _ -> onConfirmed(rowStates) }
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
        if (options.any { it.destructive }) dialog.styleSwitchlyDestructivePositiveButton() else dialog.styleSwitchlyDialogButtons()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * widthFraction).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
    return dialog
}

fun Context.showSwitchlyOptionDialog(
    @StringRes titleRes: Int,
    options: List<SwitchlyDialogOption>,
    onCancelled: (() -> Unit)? = null,
    compact: Boolean = false,
    showCancelButton: Boolean = true,
    widthFraction: Float = 0.94f,
    confirmSelection: Boolean = options.any { it.selected },
    onSelected: (index: Int) -> Unit
): AlertDialog = showSwitchlyOptionDialog(
    getString(titleRes),
    options,
    onCancelled,
    compact,
    showCancelButton,
    widthFraction,
    confirmSelection,
    onSelected
)

/**
 * Apply Switchly's *one* consistent dialog button style everywhere.
 * Design rules (matches the Google/Account popups look):
 * - Positive action: filled with current accent, readable on-accent text (black/white)
 * - Negative/Neutral: text-only (no background), accent-colored text
 * - Same typography + padding everywhere, independent of OEM defaults
 * - Ensure list choice indicators (radio/checkbox) never fall back to OEM green
 */
fun AlertDialog.styleSwitchlyDialogButtons() {
    val accent = AccentColor.getAccentColorInt(context)
    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun styleCommon(b: Button) {
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val hp = dp(16)
        val vp = dp(10)
        b.setPaddingRelative(hp, vp, hp, vp)
        b.minHeight = dp(40)
        runCatching {
            TextViewCompat.setTextAppearance(b, com.google.android.material.R.style.TextAppearance_MaterialComponents_Button)
        }
    }

    val neg = getButton(AlertDialog.BUTTON_NEGATIVE)
    val neu = getButton(AlertDialog.BUTTON_NEUTRAL)
    val pos = getButton(AlertDialog.BUTTON_POSITIVE)

    pos?.let { b ->
        styleCommon(b)
        b.setTextColor(onAccent)
        b.backgroundTintList = ColorStateList.valueOf(accent)
    }

    listOfNotNull(neg, neu).forEach { b ->
        styleCommon(b)
        b.setTextColor(accent)
        b.backgroundTintList = null
        // Some OEMs keep an old tint; force transparent.
        runCatching { b.setBackgroundColor(Color.TRANSPARENT) }
    }

    // Consistent spacing between buttons.
    val gap = dp(8)
    val ordered = listOfNotNull(neu, neg, pos).filter { it.isVisible }
    ordered.forEachIndexed { idx, b ->
        val lp = b.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.marginStart = if (idx == 0) 0 else gap
            b.layoutParams = lp
        }
    }

    runCatching { CustomAccentApplier.applyToDialog(this) }
}

fun AlertDialog.applySwitchlyDialogWidth(widthFraction: Float = 0.94f) {
    val targetWidth = (context.resources.displayMetrics.widthPixels * widthFraction).toInt()
    window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
}

// Backwards-compat alias used in older code paths.
fun AlertDialog.accentButtons() = styleSwitchlyDialogButtons()


fun Context.styleSwitchlyFormButtons(
    deleteButton: MaterialButton?,
    cancelButton: MaterialButton?,
    saveButton: MaterialButton?,
    destructiveButtonVisible: Boolean = true,
    saveIsAdd: Boolean = false
) {
    val accent = AccentColor.getAccentColorInt(this)
    val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
    val error = Color.rgb(186, 26, 26)

    deleteButton?.let { button ->
        button.isVisible = destructiveButtonVisible
        button.isAllCaps = false
        button.setTextColor(error)
        button.strokeColor = null
        button.backgroundTintList = null
        runCatching { button.setBackgroundColor(Color.TRANSPARENT) }
        button.iconTint = ColorStateList.valueOf(error)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(error, 0x22))
    }

    cancelButton?.let { button ->
        button.isAllCaps = false
        button.setTextColor(accent)
        button.strokeColor = null
        button.backgroundTintList = null
        runCatching { button.setBackgroundColor(Color.TRANSPARENT) }
        button.iconTint = ColorStateList.valueOf(accent)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x22))
    }

    saveButton?.let { button ->
        button.isAllCaps = false
        button.strokeColor = null
        button.backgroundTintList = ColorStateList.valueOf(accent)
        button.setTextColor(onAccent)
        button.iconTint = ColorStateList.valueOf(onAccent)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x44))
        if (saveIsAdd) button.setText(R.string.create)
    }
}

fun Context.showSwitchlyFormDialog(
    title: CharSequence,
    content: View,
    cancelButton: MaterialButton,
    saveButton: MaterialButton,
    saveIsAdd: Boolean = false,
    widthFraction: Float = 0.94f
): AlertDialog {
    val hostActivity = findActivity()
    hostActivity?.let { activity ->
        runCatching { CustomAccentApplier.applyToView(content, activity) }
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(content)
        .create()
    dialog.setOnShowListener {
        dialog.applySwitchlyDialogWidth(widthFraction)
        runCatching { CustomAccentApplier.applyToDialog(dialog) }
        styleSwitchlyFormButtons(
            deleteButton = null,
            cancelButton = cancelButton,
            saveButton = saveButton,
            destructiveButtonVisible = false,
            saveIsAdd = saveIsAdd
        )
        longArrayOf(0L, 120L, 360L, 720L).forEach { delay ->
            content.postDelayed(
                {
                    hostActivity?.let { activity ->
                        runCatching { CustomAccentApplier.applyToView(content, activity) }
                    }
                },
                delay
            )
        }
    }
    dialog.show()
    cancelButton.setOnClickListener { dialog.dismiss() }
    return dialog
}

fun Context.showInfoDialog(@StringRes titleRes: Int, @StringRes messageRes: Int) {
    Dialogs.builder(this)
        .setTitle(titleRes)
        .setMessage(messageRes)
        .setPositiveButton(android.R.string.ok, null)
        .showAccented()
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
