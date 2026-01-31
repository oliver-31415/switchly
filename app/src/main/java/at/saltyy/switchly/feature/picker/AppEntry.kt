package at.saltyy.switchly.feature.picker

import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String
) {
    val pkgLower: String = packageName.lowercase(Locale.getDefault())
    val labelLower: String = label.lowercase(Locale.getDefault())
}