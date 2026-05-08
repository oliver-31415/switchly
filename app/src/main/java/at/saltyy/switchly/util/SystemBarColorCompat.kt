package at.saltyy.switchly.util

import android.os.Build
import android.view.Window

object SystemBarColorCompat {
    fun setStatusBarColor(window: Window, color: Int) {
        if (Build.VERSION.SDK_INT < 35) {
            runCatching {
                Window::class.java
                    .getMethod("setStatusBarColor", Int::class.javaPrimitiveType)
                    .invoke(window, color)
            }
        }
    }

    fun setNavigationBarColor(window: Window, color: Int) {
        if (Build.VERSION.SDK_INT < 35) {
            runCatching {
                Window::class.java
                    .getMethod("setNavigationBarColor", Int::class.javaPrimitiveType)
                    .invoke(window, color)
            }
        }
    }
}
