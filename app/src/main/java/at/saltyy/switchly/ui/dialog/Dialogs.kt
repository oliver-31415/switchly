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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Central place for all dialogs so they are consistent and never fall back to OEM green.
object Dialogs {
    fun builder(ctx: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(ctx)
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

// Backwards-compat alias used in older code paths.
fun AlertDialog.accentButtons() = styleSwitchlyDialogButtons()

fun Context.showInfoDialog(@StringRes titleRes: Int, @StringRes messageRes: Int) {
    Dialogs.builder(this)
        .setTitle(titleRes)
        .setMessage(messageRes)
        .setPositiveButton(android.R.string.ok, null)
        .showAccented()
}
