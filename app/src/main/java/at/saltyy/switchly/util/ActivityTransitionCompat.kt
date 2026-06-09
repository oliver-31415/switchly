package at.saltyy.switchly.util

import android.app.Activity
import android.os.Build

object ActivityTransitionCompat {

    private const val OVERRIDE_TRANSITION_CLOSE = 1

    fun finishWithoutAnimation(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                Activity::class.java
                    .getMethod(
                        "overrideActivityTransition",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .invoke(activity, OVERRIDE_TRANSITION_CLOSE, 0, 0)
            }
        } else {
            runCatching {
                Activity::class.java
                    .getMethod(
                        "overridePendingTransition",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .invoke(activity, 0, 0)
            }
        }
    }
}
