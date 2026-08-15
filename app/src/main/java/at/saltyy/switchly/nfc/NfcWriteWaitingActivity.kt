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

import android.app.PendingIntent
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
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
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
 * Handles NFC foreground dispatch and writing, then returns the result to [NfcWriterActivity].
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

        btnClose.setOnClickListener {
            safeDisableForegroundDispatch()
            setResult(RESULT_CANCELED, Intent().apply { putExtra(EXTRA_RESULT, RESULT_FAILED_STR) })
            finish()
        }

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

    override fun onResume() {
        super.onResume()
        enableForegroundDispatchIfReady()
    }

    override fun onPause() {
        safeDisableForegroundDispatch()
        super.onPause()
    }

    override fun onStop() {
        safeDisableForegroundDispatch()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val tag = intent?.let {
            IntentCompat.getParcelableExtra(it, NfcAdapter.EXTRA_TAG, Tag::class.java)
        }
        if (tag == null) {
            Toast.makeText(this, getString(R.string.nfc_tag_error), Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessingTag) {
            return
        }

        val actualUid = NfcTagUid.normalizeUidHex(NfcTagUid.uidHex(tag))
        if (expectedUid.isNotBlank() && actualUid != expectedUid) {
            showWrongTag(actualUid)
            return
        }

        isProcessingTag = true
        safeDisableForegroundDispatch()
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

        tvTitle.text = getString(R.string.nfc_write_transient_title)
        tvHint.text = getString(R.string.nfc_write_transient_retry)
        handler.postDelayed({ resetForNextTag() }, 900L)
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

        safeDisableForegroundDispatch()
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
        safeDisableForegroundDispatch()
        tvTitle.text = getString(R.string.nfc_rewrite_wrong_tag_title)
        tvHint.text = getString(
            R.string.nfc_rewrite_wrong_tag_message,
            expectedUid,
            actualUid.ifBlank { getString(R.string.nfc_rewrite_unknown_uid) },
        )
        handler.postDelayed({ resetForNextTag() }, 1_400L)
    }

    private fun showWaitingState() {
        progress.isIndeterminate = true
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
        isProcessingTag = false
        showWaitingState()
        enableForegroundDispatchIfReady()
    }

    private fun enableForegroundDispatchIfReady() {
        if (isFinishing || isDestroyed || isProcessingTag ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return
        }

        val adapter = nfcAdapter ?: return
        val dispatchIntent = Intent(this, this::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            dispatchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        runCatching {
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }

    private fun safeDisableForegroundDispatch() {
        runCatching { nfcAdapter?.disableForegroundDispatch(this) }
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
        safeDisableForegroundDispatch()
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
        safeDisableForegroundDispatch()
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
