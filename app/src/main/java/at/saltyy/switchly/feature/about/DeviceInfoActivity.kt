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

package at.saltyy.switchly.feature.about

import android.os.Build
import at.saltyy.switchly.R
import java.util.Locale

class DeviceInfoActivity : TilesInfoActivity() {

    override fun screenTitle(): String = getString(R.string.about_device_info_title)

    override fun tiles(): List<Tile> {
        val android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val abi = Build.SUPPORTED_ABIS.joinToString()
        Build.DEVICE.orEmpty()

        val brand = Build.BRAND.orEmpty()
        Build.PRODUCT.orEmpty()
        Build.HARDWARE.orEmpty()
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val securityPatch = runCatching { Build.VERSION.SECURITY_PATCH }.getOrNull().orEmpty()
        val locale = runCatching { Locale.getDefault().toLanguageTag() }.getOrDefault("")

        return listOf(
            Tile(getString(R.string.about_android_label), android),
            Tile(getString(R.string.about_security_patch_label), securityPatch.ifBlank { "-" }),
            Tile(getString(R.string.about_manufacturer_label), manufacturer),
            Tile(getString(R.string.about_model_label), model),
            Tile(getString(R.string.about_brand_label), brand.ifBlank { "-" }),
            // Tile(getString(R.string.about_device_label), device),
            // Tile(getString(R.string.about_product_label), product.ifBlank { "-" }),
            // Tile(getString(R.string.about_hardware_label), hardware.ifBlank { "-" }),
            Tile(getString(R.string.about_abi_label), abi),
            Tile(getString(R.string.about_locale_label), locale.ifBlank { "-" }),
            Tile(getString(R.string.about_fingerprint_label), fingerprint.ifBlank { "-" }, showCopyButton = true),
        )
    }
}
