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

package at.saltyy.switchly.feature.picker

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import android.util.LruCache

/**
 * Tiny in-memory icon cache for the App Picker lists.
 * Without caching, PackageManager.getApplicationIcon() on every bind can feel janky when scrolling larger app lists.
 */
object AppIconCache {

    private val cache = LruCache<String, Drawable>(128)

    fun get(context: Context, packageName: String): Drawable {
        cache.get(packageName)?.let { return it }

        val icon = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Throwable) {
            // Fallback (should be very rare)
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        }

        // cache even nulls as a fallback drawable if possible
        if (icon != null) {
            cache.put(packageName, icon)
            return icon
        }

        return ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            ?: throw IllegalStateException("Missing default app icon")
    }
}
