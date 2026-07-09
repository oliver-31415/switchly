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

package at.saltyy.switchly.feature.schedule

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Geocoder.GeocodeListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LocationMapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LATITUDE = "at.saltyy.switchly.extra.LATITUDE"
        const val EXTRA_LONGITUDE = "at.saltyy.switchly.extra.LONGITUDE"
        const val EXTRA_LABEL = "at.saltyy.switchly.extra.LABEL"

        private const val EXTRA_INITIAL_LATITUDE = "at.saltyy.switchly.extra.INITIAL_LATITUDE"
        private const val EXTRA_INITIAL_LONGITUDE = "at.saltyy.switchly.extra.INITIAL_LONGITUDE"
        private const val EXTRA_INITIAL_LABEL = "at.saltyy.switchly.extra.INITIAL_LABEL"
        private const val MAP_LOAD_TIMEOUT_MS = 15_000L

        fun createIntent(
            context: Context,
            initialLatitude: Double?,
            initialLongitude: Double?,
            initialLabel: String?
        ): Intent = Intent(context, LocationMapPickerActivity::class.java).apply {
            if (initialLatitude != null && initialLongitude != null) {
                putExtra(EXTRA_INITIAL_LATITUDE, initialLatitude)
                putExtra(EXTRA_INITIAL_LONGITUDE, initialLongitude)
            }
            if (!initialLabel.isNullOrBlank()) {
                putExtra(EXTRA_INITIAL_LABEL, initialLabel)
            }
        }
    }

    private lateinit var selectionView: TextView
    private lateinit var layoutQuery: TextInputLayout
    private lateinit var inputQuery: EditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var btnUseSelection: MaterialButton

    private val mapLoadHandler = Handler(Looper.getMainLooper())
    private var googleMap: GoogleMap? = null
    private var mapLoaded = false
    private var pickedLatLng: LatLng? = null
    private var pickedLabel: String? = null

    private val mapLoadTimeout = Runnable {
        if (!mapLoaded && !isFinishing && !isDestroyed) {
            Toast.makeText(
                this,
                getString(R.string.schedules_location_map_picker_load_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_map_picker)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarLocationMapPicker)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        CustomAccentApplier.applyIfNeeded(this)
        toolbar.setNavigationOnClickListener { finish() }

        selectionView = findViewById(R.id.tvMapPickerSelection)
        layoutQuery = findViewById(R.id.layoutMapLocationQuery)
        inputQuery = findViewById(R.id.inputMapLocationQuery)
        btnSearch = findViewById(R.id.btnMapPickerSearch)
        btnUseSelection = findViewById(R.id.btnMapPickerUseSelection)

        val initialLatitude = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, Double.NaN)
        val initialLongitude = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, Double.NaN)
        val initialLabel = intent.getStringExtra(EXTRA_INITIAL_LABEL)

        val hasInitialPoint = !initialLatitude.isNaN() && !initialLongitude.isNaN()
        if (hasInitialPoint) {
            pickedLatLng = LatLng(initialLatitude, initialLongitude)
            pickedLabel = initialLabel
        }
        if (!initialLabel.isNullOrBlank()) {
            inputQuery.setText(initialLabel)
        }
        updateSelectionLabel(pickedLatLng, pickedLabel)

        inputQuery.addTextChangedListener { layoutQuery.error = null }
        inputQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                runSearch()
                true
            } else {
                false
            }
        }
        btnSearch.setOnClickListener { runSearch() }
        btnUseSelection.setOnClickListener { finishWithSelection() }

        setupMap(hasInitialPoint)
    }

    override fun onDestroy() {
        mapLoadHandler.removeCallbacks(mapLoadTimeout)
        googleMap?.setOnMapClickListener(null)
        googleMap?.setOnMapLoadedCallback(null)
        super.onDestroy()
    }

    private fun setupMap(hasInitialPoint: Boolean) {
        val startLatLng = pickedLatLng ?: LatLng(48.2082, 16.3738)
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as? SupportMapFragment

        if (mapFragment == null) {
            Toast.makeText(
                this,
                getString(R.string.schedules_location_map_picker_load_failed),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        mapLoadHandler.postDelayed(mapLoadTimeout, MAP_LOAD_TIMEOUT_MS)

        mapFragment.getMapAsync { map ->
            googleMap = map
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            map.setMinZoomPreference(6f)

            with(map.uiSettings) {
                isZoomControlsEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isTiltGesturesEnabled = false
                isRotateGesturesEnabled = false
                isCompassEnabled = true
                isMapToolbarEnabled = false
                isMyLocationButtonEnabled = false
            }

            map.setOnMapLoadedCallback {
                mapLoaded = true
                mapLoadHandler.removeCallbacks(mapLoadTimeout)
            }

            map.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng, if (hasInitialPoint) 17f else 12f))
            pickedLatLng?.let { renderMarker(it, pickedLabel, moveCamera = false) }

            map.setOnMapClickListener { point ->
                pickedLatLng = point
                renderMarker(point)
            }
        }
    }

    private fun runSearch() {
        val query = inputQuery.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            layoutQuery.error = getString(R.string.schedules_location_picker_invalid)
            return
        }

        layoutQuery.error = null
        btnSearch.isEnabled = false
        lifecycleScope.launch {
            val resolved = resolveLocationQuery(query)
            btnSearch.isEnabled = true
            if (resolved == null) {
                layoutQuery.error = getString(R.string.schedules_location_picker_not_found)
                return@launch
            }

            val point = LatLng(resolved.latitude, resolved.longitude)
            pickedLatLng = point
            renderMarker(point, resolved.label, moveCamera = true)
        }
    }

    private fun finishWithSelection() {
        val point = pickedLatLng
        if (point == null) {
            selectionView.text = getString(R.string.schedules_location_map_picker_no_selection)
            return
        }

        val fallbackLabel = getString(
            R.string.schedules_location_coords_fmt,
            point.latitude,
            point.longitude
        )

        lifecycleScope.launch {
            val label = reverseGeocodeLabel(point.latitude, point.longitude, fallbackLabel)
            val result = Intent().apply {
                putExtra(EXTRA_LATITUDE, point.latitude)
                putExtra(EXTRA_LONGITUDE, point.longitude)
                putExtra(EXTRA_LABEL, label ?: fallbackLabel)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun renderMarker(point: LatLng, label: String? = null, moveCamera: Boolean = false) {
        googleMap?.let { map ->
            map.clear()
            map.addMarker(MarkerOptions().position(point))
            if (moveCamera) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 17f))
            }
        }
        updateSelectionLabel(point, label)
    }

    private fun updateSelectionLabel(point: LatLng?, label: String? = null) {
        if (point == null) {
            selectionView.text = getString(R.string.schedules_location_map_picker_no_selection)
            return
        }

        val displayLabel = label ?: getString(
            R.string.schedules_location_coords_fmt,
            point.latitude,
            point.longitude
        )
        pickedLabel = displayLabel
        selectionView.text = getString(R.string.schedules_location_map_picker_selected, displayLabel)
    }

    private suspend fun resolveLocationQuery(query: String): ResolvedLocation? = withContext(Dispatchers.IO) {
        runCatching {
            val geocoder = Geocoder(this@LocationMapPickerActivity, Locale.getDefault())
            getFromLocationNameBlockingCompat(geocoder, query).firstOrNull()?.let { address ->
                ResolvedLocation(
                    latitude = address.latitude,
                    longitude = address.longitude,
                    label = formatGeocoderLabel(address) ?: query.trim()
                )
            }
        }.getOrNull()
    }

    private suspend fun reverseGeocodeLabel(
        latitude: Double,
        longitude: Double,
        fallback: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val geocoder = Geocoder(this@LocationMapPickerActivity, Locale.getDefault())
            getFromLocationBlockingCompat(geocoder, latitude, longitude).firstOrNull()?.let { address ->
                formatGeocoderLabel(address) ?: fallback
            }
        }.getOrNull()
    }

    private suspend fun getFromLocationNameBlockingCompat(
        geocoder: Geocoder,
        query: String
    ): List<Address> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(query, 1, object : GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
            }
        }

        return runCatching {
            Geocoder::class.java
                .getMethod(
                    "getFromLocationName",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                .invoke(geocoder, query, 1)
                .asAddressList()
        }.getOrDefault(emptyList())
    }

    private suspend fun getFromLocationBlockingCompat(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double
    ): List<Address> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(latitude, longitude, 1, object : GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
            }
        }

        return runCatching {
            Geocoder::class.java
                .getMethod(
                    "getFromLocation",
                    Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(geocoder, latitude, longitude, 1)
                .asAddressList()
        }.getOrDefault(emptyList())
    }

    private fun Any?.asAddressList(): List<Address> {
        return (this as? List<*>)
            ?.filterIsInstance<Address>()
            .orEmpty()
    }

    private fun formatGeocoderLabel(address: Address): String? {
        val line = address.getAddressLine(0)
        if (!line.isNullOrBlank()) return line

        return listOfNotNull(
            address.featureName,
            address.locality,
            address.adminArea,
            address.countryName
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private data class ResolvedLocation(
        val latitude: Double,
        val longitude: Double,
        val label: String?
    )
}
