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

package at.saltyy.switchly.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.FormatException
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.NfcDiagnosticsStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen "ready to write" screen.
 * Handles NFC reader mode and writing, then returns the result to [NfcWriterActivity].
 */
class NfcWriteWaitingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URI_TO_WRITE = "uri_to_write"
        const val EXTRA_EXPECTED_UID = "expected_uid"
        const val EXTRA_FALLBACK_ACTION = "fallback_action"
        const val EXTRA_FALLBACK_PROFILE = "fallback_profile"
        const val EXTRA_FALLBACK_DURATION_MINUTES = "fallback_duration_minutes"
        const val EXTRA_FALLBACK_ASK_DURATION = "fallback_ask_duration"

        const val MODE_WRITE_URI = "write_uri"
        const val MODE_PAIR_UID_READONLY = "pair_uid_readonly"
        const val MODE_PAIR_UID_WRITABLE = "pair_uid_writable"

        const val EXTRA_RESULT = "result"
        const val EXTRA_UID = "uid"
        const val EXTRA_ALREADY_PAIRED = "already_paired"

        const val RESULT_OK_STR = "ok"
        const val RESULT_TOO_SMALL_STR = "too_small"
        const val RESULT_NOT_WRITABLE_STR = "not_writable"
        const val RESULT_UNSUPPORTED_STR = "unsupported"
        const val RESULT_TRANSIENT_STR = "transient"
        const val RESULT_FAILED_STR = "failed"

        private const val MAX_TRANSIENT_FAILURES_BEFORE_FALLBACK = 3

        @Volatile
        private var writeSessionActive: Boolean = false

        private const val SUCCESS_TAG_DEBOUNCE_MS = 1200

        fun isWriteSessionActive(): Boolean = writeSessionActive
    }

    private enum class WriteResult {
        OK,
        TOO_SMALL,
        NOT_WRITABLE,
        UNSUPPORTED,
        TRANSIENT_FAILURE,
        FAILED,
    }

    private enum class WritableCapability {
        WRITABLE,
        NOT_WRITABLE,
        TRANSIENT_FAILURE,
    }

    private var nfcAdapter: NfcAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isProcessingTag = false
    private var transientFailureCount = 0
    private var lastTransientUid: String = ""

    private lateinit var progress: CircularProgressIndicator
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnClose: android.view.View
    private lateinit var btnRetry: android.view.View

    private var mode: String = MODE_WRITE_URI
    private var uriToWrite: String? = null
    private var expectedUid: String = ""
    private var fallbackAction: NfcUidPairingStore.PairedTagAction? = null
    private var fallbackProfile: String? = null
    private var fallbackDurationMinutes: Int = NfcUidPairingStore.DEFAULT_READ_ONLY_TEMP_DURATION_MINUTES
    private var fallbackAskDuration: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        at.saltyy.switchly.ui.ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_write_wait)

        progress = findViewById(R.id.waitProgress)
        tvTitle = findViewById(R.id.waitTitle)
        tvHint = findViewById(R.id.waitHint)
        btnClose = findViewById(R.id.closeButton)
        btnRetry = findViewById(R.id.retryButton)

        btnClose.setOnClickListener {
            safeDisableReaderMode()
            setResult(RESULT_CANCELED, Intent().apply { putExtra(EXTRA_RESULT, RESULT_FAILED_STR) })
            finish()
        }
        btnRetry.setOnClickListener {
            resetForNextTag()
        }

        writeSessionActive = true
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WRITE_URI
        uriToWrite = intent.getStringExtra(EXTRA_URI_TO_WRITE)
        expectedUid = NfcTagUid.normalizeUidHex(intent.getStringExtra(EXTRA_EXPECTED_UID))
        fallbackAction = intent.getStringExtra(EXTRA_FALLBACK_ACTION)
            ?.let { NfcUidPairingStore.PairedTagAction.fromId(it) }
        fallbackProfile = intent.getStringExtra(EXTRA_FALLBACK_PROFILE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        fallbackDurationMinutes = intent.getIntExtra(
            EXTRA_FALLBACK_DURATION_MINUTES,
            NfcUidPairingStore.DEFAULT_READ_ONLY_TEMP_DURATION_MINUTES,
        ).coerceIn(1, 1440)
        fallbackAskDuration = intent.getBooleanExtra(EXTRA_FALLBACK_ASK_DURATION, false)
        showWaitingState()
    }

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        // Reader callbacks are not guaranteed to run on the main thread.
        // Keep all UI/lifecycle state changes on the activity thread and ignore duplicate discoveries while processing.
        runOnUiThread { handleDiscoveredTag(tag) }
    }

    override fun onPostResume() {
        super.onPostResume()
        // AppCompat/Lifecycle can still report STARTED from inside onResume().
        // Reader mode must be enabled only once the Activity is fully resumed, otherwise the call can be skipped and Android falls back to the manifest NFC dispatch (NfcEntryActivity).
        enableReaderModeIfReady()
    }

    override fun onPause() {
        safeDisableReaderMode()
        super.onPause()
    }

    override fun onStop() {
        safeDisableReaderMode()
        super.onStop()
    }

    override fun onDestroy() {
        safeDisableReaderMode()
        writeSessionActive = false
        super.onDestroy()
    }

    private fun handleDiscoveredTag(tag: Tag) {
        if (isFinishing || isDestroyed || isProcessingTag) {
            return
        }

        val actualUid = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
        if (expectedUid.isNotBlank() && actualUid != expectedUid) {
            showWrongTag(actualUid)
            return
        }

        isProcessingTag = true
        // Keep reader mode active for the complete NFC transaction.
        // Disabling reader mode here can tear down the RF connection before Ndef.connect()/writeNdefMessage() finishes and surface as a false TagLostException even when the tag has not moved. 
        // Duplicate callbacks are already ignored by isProcessingTag.
        btnRetry.visibility = android.view.View.GONE
        tvTitle.text = getString(R.string.nfc_writing)
        tvHint.text = getString(R.string.nfc_hold_still)
        progress.isIndeterminate = true

        lifecycleScope.launch {
            processTag(tag)
        }
    }

    private suspend fun processTag(tag: Tag) {
        if (mode == MODE_PAIR_UID_READONLY || mode == MODE_PAIR_UID_WRITABLE) {
            processPairing(tag)
            return
        }

        val uri = uriToWrite
        if (uri.isNullOrBlank()) {
            finishWithError(RESULT_FAILED_STR)
            return
        }

        val result = withContext(Dispatchers.IO) { writeUriToTag(uri, tag) }
        val uid = NfcTagUid.uidHex(tag)
        when (result) {
            WriteResult.OK -> handleSuccessfulWrite(tag, uid)
            WriteResult.TOO_SMALL -> showUidFallback(
                RESULT_TOO_SMALL_STR,
                uid,
                NfcUidPairingStore.TagKind.WRITABLE,
            )
            WriteResult.NOT_WRITABLE -> showUidFallback(
                RESULT_NOT_WRITABLE_STR,
                uid,
                NfcUidPairingStore.TagKind.READ_ONLY,
            )
            WriteResult.UNSUPPORTED -> showUidFallback(
                RESULT_UNSUPPORTED_STR,
                uid,
                NfcUidPairingStore.TagKind.READ_ONLY,
            )
            WriteResult.TRANSIENT_FAILURE -> handleTransientWriteFailure(uid)
            WriteResult.FAILED -> finishWithError(RESULT_FAILED_STR)
        }
    }

    private suspend fun processPairing(tag: Tag) {
        val uid = NfcTagUid.uidHex(tag)
        if (uid.isNullOrBlank()) {
            NfcDiagnosticsStore.recordWriteResult(this, RESULT_FAILED_STR)
            finishWithError(RESULT_FAILED_STR)
            return
        }

        if (mode == MODE_PAIR_UID_WRITABLE) {
            when (withContext(Dispatchers.IO) { writableCapability(tag) }) {
                WritableCapability.WRITABLE -> Unit
                WritableCapability.NOT_WRITABLE -> {
                    NfcDiagnosticsStore.recordWriteResult(this, RESULT_NOT_WRITABLE_STR)
                    showUidFallback(
                        RESULT_NOT_WRITABLE_STR,
                        uid,
                        NfcUidPairingStore.TagKind.READ_ONLY,
                    )
                    return
                }
                WritableCapability.TRANSIENT_FAILURE -> {
                    handleTransientWriteFailure(uid)
                    return
                }
            }
        }

        pairUidAndFinish(
            uid = uid,
            tagKind = if (mode == MODE_PAIR_UID_WRITABLE) {
                NfcUidPairingStore.TagKind.WRITABLE
            } else {
                NfcUidPairingStore.TagKind.READ_ONLY
            },
        )
    }

    private fun handleSuccessfulWrite(tag: Tag, uid: String?) {
        // The write is complete, but the physical tag is usually still on the antenna.
        // If reader mode is torn down immediately Android can rediscover the same tag through normal NFC dispatch, which causes a second NFC haptic/notification on some devices.
        // Debounce this tag while it remains in range and keep reader mode active until the Activity finishes.
        suppressRediscoveryAfterSuccess(tag)
        transientFailureCount = 0
        lastTransientUid = ""
        NfcDiagnosticsStore.recordWriteResult(this, RESULT_OK_STR)
        if (shouldAutoPairOnWrite() && !uid.isNullOrBlank()) {
            val isNew = NfcUidPairingStore.addPairedUidHex(
                this,
                uid,
                NfcUidPairingStore.TagKind.WRITABLE,
            )
            if (isNew) {
                showPairMetaPrompt(uid) {
                    finishWithOk(uidHex = uid, alreadyPaired = false, guardUidHex = uid)
                }
            } else {
                finishWithOk(uidHex = uid, alreadyPaired = true, guardUidHex = uid)
            }
            return
        }
        finishWithOk(uidHex = null, guardUidHex = uid ?: NfcTagUid.uidHex(tag))
    }

    private fun handleTransientWriteFailure(uid: String?) {
        val cleanUid = NfcTagUid.normalizeUidHex(uid)
        if (cleanUid != lastTransientUid) {
            transientFailureCount = 0
            lastTransientUid = cleanUid
        }
        transientFailureCount += 1
        NfcDiagnosticsStore.recordWriteResult(this, RESULT_TRANSIENT_STR)
        if (transientFailureCount >= MAX_TRANSIENT_FAILURES_BEFORE_FALLBACK && !uid.isNullOrBlank()) {
            showUidFallback(
                RESULT_TRANSIENT_STR,
                uid,
                NfcUidPairingStore.TagKind.WRITABLE,
            )
            return
        }

        // Stop polling after a real transient failure.
        // Automatically re-enabling reader mode while the same tag is still sitting on the antenna causes an immediate rediscovery/retry loop.
        // Let the user remove the tag and explicitly arm the writer again instead.
        safeDisableReaderMode()
        tvTitle.text = getString(R.string.nfc_write_transient_title)
        tvHint.text = getString(R.string.nfc_write_transient_retry)
        btnRetry.visibility = android.view.View.VISIBLE
    }

    private fun showUidFallback(
        result: String,
        uid: String?,
        tagKind: NfcUidPairingStore.TagKind,
    ) {
        val cleanUid = NfcTagUid.normalizeUidHex(uid)
        if (cleanUid.isBlank()) {
            finishWithError(result)
            return
        }

        safeDisableReaderMode()
        val title = when (result) {
            RESULT_TOO_SMALL_STR -> getString(R.string.nfc_write_error_too_small_title)
            RESULT_NOT_WRITABLE_STR -> getString(R.string.nfc_write_error_not_writable_title)
            RESULT_UNSUPPORTED_STR -> getString(R.string.nfc_write_error_unsupported_title)
            RESULT_TRANSIENT_STR -> getString(R.string.nfc_write_repeated_failure_title)
            else -> getString(R.string.nfc_write_error_generic)
        }
        val message = when (result) {
            RESULT_TOO_SMALL_STR -> getString(R.string.nfc_write_uid_fallback_too_small)
            RESULT_NOT_WRITABLE_STR -> getString(R.string.nfc_write_uid_fallback_read_only)
            RESULT_UNSUPPORTED_STR -> getString(R.string.nfc_write_uid_fallback_unsupported)
            RESULT_TRANSIENT_STR -> getString(R.string.nfc_write_uid_fallback_repeated)
            else -> getString(R.string.nfc_write_uid_fallback_generic)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.nfc_write_pair_uid_instead) { _, _ ->
                PreferenceManager.getDefaultSharedPreferences(this).edit {
                    putBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, true)
                }
                pairUidAndFinish(
                    uid = cleanUid,
                    tagKind = tagKind,
                    applyFallbackAction = true,
                )
            }
            .setNegativeButton(R.string.nfc_write_try_another_tag) { _, _ ->
                resetForNextTag()
            }
            .setNeutralButton(R.string.cancel) { _, _ ->
                finishWithError(result)
            }
            .setCancelable(false)
            .showAccented()
    }

    private fun pairUidAndFinish(
        uid: String,
        tagKind: NfcUidPairingStore.TagKind,
        applyFallbackAction: Boolean = false,
    ) {
        val isNew = NfcUidPairingStore.addPairedUidHex(this, uid, tagKind)
        if (applyFallbackAction) {
            fallbackAction?.let { action ->
                NfcUidPairingStore.setTagAction(
                    ctx = this,
                    uidHex = uid,
                    action = action,
                    durationMinutes = if (
                        action == NfcUidPairingStore.PairedTagAction.TEMP_DISABLE ||
                        action == NfcUidPairingStore.PairedTagAction.TEMP_ENABLE
                    ) {
                        fallbackDurationMinutes
                    } else {
                        null
                    },
                    askDurationWhenScanned = fallbackAskDuration,
                    profile = fallbackProfile,
                )
            }
        }
        NfcDiagnosticsStore.recordWriteResult(this, "paired_uid")
        if (isNew) {
            showPairMetaPrompt(uid) {
                finishWithOk(uidHex = uid, alreadyPaired = false, guardUidHex = uid)
            }
        } else {
            finishWithOk(uidHex = uid, alreadyPaired = true, guardUidHex = uid)
        }
    }

    private fun showWrongTag(actualUid: String) {
        isProcessingTag = true
        safeDisableReaderMode()
        tvTitle.text = getString(R.string.nfc_rewrite_wrong_tag_title)
        tvHint.text = getString(
            R.string.nfc_rewrite_wrong_tag_message,
            expectedUid,
            actualUid.ifBlank { getString(R.string.nfc_rewrite_unknown_uid) },
        )
        btnRetry.visibility = android.view.View.VISIBLE
    }

    private fun showWaitingState() {
        progress.isIndeterminate = true
        if (::btnRetry.isInitialized) btnRetry.visibility = android.view.View.GONE
        tvTitle.text = getString(R.string.nfc_waiting_tag)
        tvHint.text = when (mode) {
            MODE_PAIR_UID_WRITABLE -> getString(R.string.nfc_pair_waiting_writable)
            MODE_PAIR_UID_READONLY -> getString(R.string.nfc_pair_waiting_readonly)
            else -> if (expectedUid.isBlank()) {
                getString(R.string.nfc_ready_to_write)
            } else {
                getString(R.string.nfc_rewrite_waiting_expected, expectedUid)
            }
        }
    }

    private fun resetForNextTag() {
        if (isFinishing || isDestroyed) {
            return
        }
        safeDisableReaderMode()
        isProcessingTag = false
        showWaitingState()
        enableReaderModeIfReady()
    }

    private fun enableReaderModeIfReady() {
        if (isFinishing || isDestroyed || isProcessingTag) {
            return
        }

        val adapter = nfcAdapter ?: return
        val readerFlags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        // Do not use FLAG_READER_SKIP_NDEF_CHECK here: the writer needs Android to enumerate the Ndef technology so existing/formatted tags can be written via Ndef.get(tag).
        runCatching {
            adapter.enableReaderMode(this, readerCallback, readerFlags, null)
        }.onSuccess {
            AppLogStore.append(this, "NFC", "Writer reader mode enabled")
        }.onFailure { error ->
            NfcDiagnosticsStore.recordFailure(this, "writer_reader_mode_enable_failed")
            AppLogStore.append(
                this,
                "NFC",
                "Writer reader mode failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }

    private fun safeDisableReaderMode() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun suppressRediscoveryAfterSuccess(tag: Tag) {
        runCatching {
            // minSdk is 27, so NfcAdapter.ignore() is available on every supported Switchly device.
            // Once the write has completed we no longer need to communicate with this tag.
            // Ignore it until it has genuinely left the field to prevent duplicate reader/intent discovery.
            nfcAdapter?.ignore(tag, SUCCESS_TAG_DEBOUNCE_MS, null, handler)
        }.onFailure { error ->
            AppLogStore.append(
                this,
                "NFC",
                "Writer success debounce failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }

    private fun shouldAutoPairOnWrite(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(BlockingToggleKeys.KEY_AUTO_PAIR_ON_WRITE, false)
    }

    private fun showPairMetaPrompt(uid: String, onDone: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_paired_tag_pair_meta, FrameLayout(this), false)
        view.findViewById<TextView>(R.id.tvUid).text = uid

        val etName = view.findViewById<TextInputEditText>(R.id.etTagName)
        val etNote = view.findViewById<TextInputEditText>(R.id.etTagNote)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.paired_tag_pair_prompt_title))
            .setMessage(getString(R.string.paired_tag_pair_prompt_message))
            .setView(view)
            .setPositiveButton(getString(R.string.paired_tag_pair_prompt_save)) { _, _ ->
                NfcUidPairingStore.setTagMeta(
                    this,
                    uid,
                    etName.text?.toString(),
                    etNote.text?.toString(),
                )
                onDone()
            }
            .setNegativeButton(getString(R.string.paired_tag_pair_prompt_skip)) { _, _ -> onDone() }
            .setCancelable(false)
            .showAccented()
    }

    private fun finishWithOk(
        uidHex: String?,
        alreadyPaired: Boolean = false,
        guardUidHex: String? = uidHex,
    ) {
        // Do not disable reader mode here while the tag may still be touching the phone. onPause() will tear it down when this Activity actually finishes. 
        // This avoids an immediate fallback to normal NFC dispatch and the resulting duplicate haptic/scan.
        NfcRecentWriteGuard.markUid(this, guardUidHex)
        tvTitle.text = when {
            alreadyPaired -> getString(R.string.nfc_pair_already_added)
            uidHex != null -> getString(R.string.nfc_pair_ok)
            else -> getString(R.string.nfc_write_ok)
        }
        tvHint.text = if (uidHex != null) getString(R.string.nfc_pair_ok_with_uid, uidHex) else ""
        progress.isIndeterminate = true

        Toast.makeText(
            this,
            getString(
                when {
                    alreadyPaired -> R.string.nfc_pair_already_added
                    uidHex != null -> R.string.nfc_pair_ok
                    else -> R.string.nfc_write_ok
                },
            ),
            Toast.LENGTH_SHORT,
        ).show()

        handler.postDelayed({
            val data = Intent().apply {
                putExtra(EXTRA_RESULT, RESULT_OK_STR)
                if (uidHex != null) putExtra(EXTRA_UID, uidHex)
                putExtra(EXTRA_ALREADY_PAIRED, alreadyPaired)
            }
            setResult(RESULT_OK, data)
            finish()
        }, 500L)
    }

    private fun finishWithError(result: String) {
        safeDisableReaderMode()
        tvTitle.text = when (result) {
            RESULT_TOO_SMALL_STR -> getString(R.string.nfc_write_error_too_small_title)
            RESULT_NOT_WRITABLE_STR -> getString(R.string.nfc_write_error_not_writable_title)
            RESULT_UNSUPPORTED_STR -> getString(R.string.nfc_write_error_unsupported_title)
            RESULT_TRANSIENT_STR -> getString(R.string.nfc_write_transient_title)
            else -> getString(R.string.nfc_write_error_generic)
        }
        tvHint.text = ""
        progress.isIndeterminate = true

        handler.postDelayed({
            val data = Intent().apply { putExtra(EXTRA_RESULT, result) }
            setResult(RESULT_CANCELED, data)
            finish()
        }, 600L)
    }

    private fun writableCapability(tag: Tag): WritableCapability {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (ndef.isWritable) WritableCapability.WRITABLE else WritableCapability.NOT_WRITABLE
            } catch (_: TagLostException) {
                WritableCapability.TRANSIENT_FAILURE
            } catch (_: IOException) {
                WritableCapability.TRANSIENT_FAILURE
            } catch (_: Throwable) {
                WritableCapability.NOT_WRITABLE
            } finally {
                runCatching { ndef.close() }
            }
        }
        val formatable = NdefFormatable.get(tag) ?: return WritableCapability.NOT_WRITABLE
        return try {
            formatable.connect()
            WritableCapability.WRITABLE
        } catch (_: TagLostException) {
            WritableCapability.TRANSIENT_FAILURE
        } catch (_: IOException) {
            WritableCapability.TRANSIENT_FAILURE
        } catch (_: Throwable) {
            WritableCapability.NOT_WRITABLE
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun writeUriToTag(uriString: String, tag: Tag): WriteResult {
        var ndef: Ndef? = null
        var formatable: NdefFormatable? = null

        return try {
            val message = NdefMessage(arrayOf(NdefRecord.createUri(uriString)))
            val ndefTech = Ndef.get(tag)
            if (ndefTech != null) {
                ndef = ndefTech
                ndef.connect()
                if (!ndef.isWritable) {
                    return WriteResult.NOT_WRITABLE
                }
                if (ndef.maxSize < message.toByteArray().size) {
                    return WriteResult.TOO_SMALL
                }
                ndef.writeNdefMessage(message)
                WriteResult.OK
            } else {
                val formatableTech = NdefFormatable.get(tag) ?: return WriteResult.UNSUPPORTED
                formatable = formatableTech
                formatable.connect()
                formatable.format(message)
                WriteResult.OK
            }
        } catch (_: TagLostException) {
            WriteResult.TRANSIENT_FAILURE
        } catch (_: IOException) {
            WriteResult.TRANSIENT_FAILURE
        } catch (_: SecurityException) {
            WriteResult.NOT_WRITABLE
        } catch (_: FormatException) {
            WriteResult.UNSUPPORTED
        } catch (_: Throwable) {
            WriteResult.FAILED
        } finally {
            runCatching { ndef?.close() }
            runCatching { formatable?.close() }
        }
    }
}
