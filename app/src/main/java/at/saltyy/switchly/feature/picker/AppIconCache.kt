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
