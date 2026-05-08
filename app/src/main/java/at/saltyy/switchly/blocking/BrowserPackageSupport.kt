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

package at.saltyy.switchly.blocking

internal fun isFirefoxFamily(pkg: String): Boolean =
    pkg.startsWith("org.mozilla.") || pkg == "net.waterfox.android.release"

internal fun isBrowserPackage(pkg: String): Boolean {
    return pkg == "com.android.chrome" ||
        pkg == "com.brave.browser" ||
        pkg == "com.microsoft.emmx" ||
        pkg == "com.opera.browser" ||
        pkg == "com.opera.browser.beta" ||
        pkg == "com.opera.mini.native" ||
        pkg == "com.sec.android.app.sbrowser" ||
        pkg == "com.sec.android.app.sbrowser.beta" ||
        pkg == "org.mozilla.firefox" ||
        pkg == "org.mozilla.firefox_beta" ||
        pkg == "org.mozilla.fennec_fdroid" ||
        pkg == "org.mozilla.focus" ||
        pkg == "org.mozilla.fenix" ||
        pkg == "net.waterfox.android.release" ||
        pkg == "com.kiwibrowser.browser" ||
        pkg == "com.vivaldi.browser" ||
        pkg == "com.duckduckgo.mobile.android" ||
        pkg == "com.google.android.apps.chrome" ||
        pkg == "com.chrome.beta" ||
        pkg == "com.chrome.dev"
}

internal fun browserUrlViewIds(pkg: String): List<String> {
    return when (pkg) {
        "com.android.chrome" ->
            listOf(
                "com.android.chrome:id/url_bar"
            )

        "com.brave.browser" ->
            listOf(
                "com.brave.browser:id/url_bar",
                "com.android.chrome:id/url_bar"
            )

        "com.microsoft.emmx" ->
            listOf(
                "com.microsoft.emmx:id/url_bar",
                "com.android.chrome:id/url_bar"
            )

        "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native" ->
            listOf(
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/url_bar",
                "com.opera.browser:id/address_bar"
            )

        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta" ->
            listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/location_bar"
            )

        "org.mozilla.firefox" -> listOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_origin_view",
            "org.mozilla.firefox:id/mozac_browser_toolbar_display_url_view",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"
        )

        "org.mozilla.firefox_beta" ->
            listOf(
                "org.mozilla.firefox_beta:id/mozac_browser_toolbar_origin_view",
                "org.mozilla.firefox_beta:id/mozac_browser_toolbar_display_url_view",
                "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view"
            )

        "org.mozilla.fennec_fdroid" ->
            listOf(
                "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_origin_view",
                "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_display_url_view",
                "org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_url_view"
            )

        "org.mozilla.focus" ->
            listOf(
                "org.mozilla.focus:id/urlInputView"
            )

        "org.mozilla.fenix" ->
            listOf(
                "org.mozilla.fenix:id/mozac_browser_toolbar_origin_view",
                "org.mozilla.fenix:id/mozac_browser_toolbar_display_url_view",
                "org.mozilla.fenix:id/mozac_browser_toolbar_url_view"
            )

        "net.waterfox.android.release" ->
            listOf(
                "net.waterfox.android.release:id/mozac_browser_toolbar_origin_view",
                "net.waterfox.android.release:id/mozac_browser_toolbar_display_url_view",
                "net.waterfox.android.release:id/mozac_browser_toolbar_url_view"
            )

        "com.kiwibrowser.browser" ->
            listOf(
                "com.kiwibrowser.browser:id/url_bar",
                "com.android.chrome:id/url_bar"
            )

        "com.vivaldi.browser" ->
            listOf(
                "com.vivaldi.browser:id/url_bar",
                "com.android.chrome:id/url_bar"
            )

        "com.duckduckgo.mobile.android" ->
            listOf(
                "com.duckduckgo.mobile.android:id/omnibarTextInput"
            )

        "com.google.android.apps.chrome" ->
            listOf(
                "com.android.chrome:id/url_bar"
            )

        "com.chrome.beta" ->
            listOf(
                "com.chrome.beta:id/url_bar",
                "com.android.chrome:id/url_bar"
            )

        "com.chrome.dev" ->
            listOf(
                "com.chrome.dev:id/url_bar",
                "com.android.chrome:id/url_bar"
            )

        else -> emptyList()
    }
}

internal fun firefoxEditingViewIds(pkg: String): List<String> {
    return when (pkg) {
        "org.mozilla.firefox" -> listOf("org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view")
        "org.mozilla.firefox_beta" -> listOf("org.mozilla.firefox_beta:id/mozac_browser_toolbar_edit_url_view")
        "org.mozilla.fennec_fdroid" -> listOf("org.mozilla.fennec_fdroid:id/mozac_browser_toolbar_edit_url_view")
        "org.mozilla.fenix" -> listOf("org.mozilla.fenix:id/mozac_browser_toolbar_edit_url_view")
        "org.mozilla.focus" -> listOf("org.mozilla.focus:id/urlInputView")
        "net.waterfox.android.release" -> listOf("net.waterfox.android.release:id/mozac_browser_toolbar_edit_url_view")
        else -> emptyList()
    }
}
