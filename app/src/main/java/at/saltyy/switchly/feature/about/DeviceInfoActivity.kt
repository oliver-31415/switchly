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
