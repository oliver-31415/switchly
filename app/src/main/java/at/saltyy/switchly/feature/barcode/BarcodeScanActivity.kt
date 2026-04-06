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

package at.saltyy.switchly.feature.barcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BarcodeScanCountStore
import at.saltyy.switchly.data.prefs.ScanCodeStore
import at.saltyy.switchly.nfc.NfcEntryActivity
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private val handled = AtomicBoolean(false)

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                )
                .build()
        )
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, R.string.permission_barcode_camera_required, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!canOpenScanner()) {
            finish()
            return
        }

        previewView = PreviewView(this)
        setContentView(previewView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allowDirectOpen(): Boolean = intent?.getBooleanExtra(EXTRA_ALLOW_DIRECT_OPEN, false) == true

    private fun canOpenScanner(): Boolean {
        if (isPickMode() || allowDirectOpen()) return true
        if (!allowDirectOpen() && !AutomationModeStore.isBarcodeAllowed(this)) {
            Toast.makeText(this, R.string.mode_blocked_barcode_action, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                analyze(imageProxy)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        if (handled.get()) {
            imageProxy.close()
            return
        }

        val img = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(img, imageProxy.imageInfo.rotationDegrees)

        scanner.process(input)
            .addOnSuccessListener { list ->
                val first = list.firstOrNull() ?: return@addOnSuccessListener
                val raw = first.rawValue ?: return@addOnSuccessListener
                handleBarcode(raw)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleBarcode(raw: String) {
        if (!handled.compareAndSet(false, true)) return

        if (isPickMode()) {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_PICKED_RAW, raw)
                    .putExtra(EXTRA_PICKED_KIND, ScanCodeStore.Kind.BARCODE.raw)
            )
            finish()
            return
        }

        if (!allowDirectOpen() && !AutomationModeStore.isBarcodeAllowed(this)) {
            Toast.makeText(this, R.string.mode_blocked_barcode_action, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val managed = ScanCodeStore.findEntry(this, ScanCodeStore.Kind.BARCODE, raw)
        if (managed != null) {
            val check = ScanCodeStore.checkLimits(this, managed)
            if (check != null) {
                Toast.makeText(this, check, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            ScanCodeStore.consume(this, managed)
            BarcodeScanCountStore.incrementToday(this)
            dispatchActionUri(managed.actionUri)
            return
        }

        val rawUri = raw.toUri()
        val strictSwitchly = ScanCodeStore.isStrictSwitchlyUri(raw)
        if (rawUri.scheme.equals("switchly", ignoreCase = true) &&
            strictSwitchly
        ) {
            BarcodeScanCountStore.incrementToday(this)
            dispatchActionUri(raw)
            return
        }

        Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dispatchActionUri(rawUri: String) {
        val uri = rawUri.toUri()
        startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .putExtra(EXTRA_SCAN_SOURCE, ScanCodeStore.Kind.BARCODE.raw)
                .setClass(this, NfcEntryActivity::class.java)
        )
        finish()
    }

    private fun isPickMode(): Boolean = intent?.getBooleanExtra(EXTRA_PICK_MODE, false) == true

    override fun onDestroy() {
        super.onDestroy()
        runCatching { scanner.close() }
    }

    companion object {
        const val EXTRA_ALLOW_DIRECT_OPEN = "allow_direct_open"
        const val EXTRA_PICK_MODE = "extra_pick_mode"
        const val EXTRA_PICKED_RAW = "extra_picked_raw"
        const val EXTRA_PICKED_KIND = "extra_picked_kind"
        const val EXTRA_SCAN_SOURCE = "extra_scan_source"
    }
}
