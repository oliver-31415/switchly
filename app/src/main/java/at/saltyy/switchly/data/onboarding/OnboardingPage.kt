package at.saltyy.switchly.data.onboarding

import android.app.Activity

data class OnboardingPage(
    val title: String,
    val desc: String,
    val iconRes: Int? = null,
    val level: Level = Level.INFO,
    val actionLabel: String? = null,
    val action: ((Activity) -> Unit)? = null
) {
    enum class Level { REQUIRED, RECOMMENDED, OPTIONAL, INFO }
}
