/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object AdvancedModeStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_ADVANCED_MODE_ENABLED = "pref_advanced_mode_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADVANCED_MODE_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit(commit = true) { putBoolean(KEY_ADVANCED_MODE_ENABLED, enabled) }
    }
}
