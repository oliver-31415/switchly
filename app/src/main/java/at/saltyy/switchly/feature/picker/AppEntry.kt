package at.saltyy.switchly.feature.picker

import at.saltyy.switchly.util.AppBlockSafety
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    val isAvailable: Boolean = true,
    val blockSafety: AppBlockSafety.Info = AppBlockSafety.Info()
) {
    val pkgLower: String = packageName.lowercase(Locale.getDefault())
    val labelLower: String = label.lowercase(Locale.getDefault())
}