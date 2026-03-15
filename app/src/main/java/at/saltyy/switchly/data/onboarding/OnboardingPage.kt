package at.saltyy.switchly.data.onboarding

import android.app.Activity
import android.content.Context

data class OnboardingPage(
    val type: Type = Type.STANDARD,

    val title: String,
    val desc: String,
    val iconRes: Int? = null,
    val level: Level = Level.INFO,
    val actionLabel: String? = null,
    val action: ((Activity) -> Unit)? = null,

    /** Optional completion check (e.g. a permission) used to show a "Granted" state and gate required steps. */
    val completionCheck: ((Context) -> Boolean)? = null,

    /** Optional label used when [completionCheck] returns true. Defaults to "Granted". */
    val completedLabel: String? = null,

    /** Optional message shown when the user tries to continue but the required step is incomplete. */
    val requiredMessage: String? = null
) {
    enum class Type { 
        STANDARD, USAGE_SUMMARY
    }
    enum class Level {
        REQUIRED, RECOMMENDED, OPTIONAL, INFO 
    }
}
