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
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText

/**
 * Full-screen "ready to write" screen.
 * Handles NFC foreground dispatch + writing, then returns result back to [NfcWriterActivity].
 */
class NfcWriteWaitingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URI_TO_WRITE = "uri_to_write"

        const val MODE_WRITE_URI = "write_uri"
        const val MODE_PAIR_UID_READONLY = "pair_uid_readonly"
        const val MODE_PAIR_UID_WRITABLE = "pair_uid_writable"

        const val EXTRA_RESULT = "result"
        const val EXTRA_UID = "uid"
        const val EXTRA_ALREADY_PAIRED = "already_paired"

        const val RESULT_OK_STR = "ok"
        const val RESULT_TOO_SMALL_STR = "too_small"
        const val RESULT_NOT_WRITABLE_STR = "not_writable"
        const val RESULT_FAILED_STR = "failed"
    }

    private enum class WriteResult {
        OK,
        TOO_SMALL,
        NOT_WRITABLE,
        FAILED
    }

    private var nfcAdapter: NfcAdapter? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * NFC intents can arrive back-to-back on some devices/OS versions.
     * We only want to process the first one and ignore the rest.
     */
    private var isProcessingTag: Boolean = false

    private lateinit var progress: CircularProgressIndicator
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView

    private lateinit var btnClose: android.view.View

    private var mode: String = MODE_WRITE_URI
    private var uriToWrite: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        at.saltyy.switchly.ui.ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_write_wait)

        progress = findViewById(R.id.waitProgress)
        tvTitle = findViewById(R.id.waitTitle)
        tvHint = findViewById(R.id.waitHint)
        btnClose = findViewById(R.id.closeButton)

        btnClose.setOnClickListener {
            // Allow user to back out if they landed here by mistake.
            safeDisableForegroundDispatch()
            setResult(RESULT_CANCELED, Intent().apply { putExtra(EXTRA_RESULT, RESULT_FAILED_STR) })
            finish()
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WRITE_URI
        uriToWrite = intent.getStringExtra(EXTRA_URI_TO_WRITE)

        // Initial UI state
        progress.isIndeterminate = true
        tvTitle.text = getString(R.string.nfc_waiting_tag)
        tvHint.text = when (mode) {
            MODE_PAIR_UID_WRITABLE -> getString(R.string.nfc_pair_waiting_writable)
            MODE_PAIR_UID_READONLY -> getString(R.string.nfc_pair_waiting_readonly)
            else -> getString(R.string.nfc_ready_to_write) // already in project
        }
    }

    override fun onResume() {
        super.onResume()

        if (isFinishing || isDestroyed || isProcessingTag) return

        val adapter = nfcAdapter ?: return
        val intent = Intent(this, this::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        runCatching {
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
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
        if (intent == null) return

        val tag: Tag? = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)

        if (tag == null) {
            Toast.makeText(this, getString(R.string.nfc_tag_error), Toast.LENGTH_SHORT).show()
            return
        }

        // Avoid double-processing (and avoid calling disableForegroundDispatch in a bad state).
        if (isProcessingTag) return
        isProcessingTag = true

        // Stop foreground dispatch immediately so follow-up scans cannot get routed back into the write screen while this write result is still being shown.
        safeDisableForegroundDispatch()

        tvTitle.text = getString(R.string.nfc_writing)
        tvHint.text = getString(R.string.nfc_hold_still)
        progress.isIndeterminate = true

        handler.post {
            if (mode == MODE_PAIR_UID_READONLY || mode == MODE_PAIR_UID_WRITABLE) {
                val uid = NfcTagUid.uidHex(tag)
                if (uid == null) {
                    finishWithError(RESULT_FAILED_STR)
                    return@post
                }

                if (mode == MODE_PAIR_UID_WRITABLE && !isWritableCapable(tag)) {
                    finishWithError(RESULT_NOT_WRITABLE_STR)
                    return@post
                }

                val tagKind = if (mode == MODE_PAIR_UID_WRITABLE) {
                    NfcUidPairingStore.TagKind.WRITABLE
                } else {
                    NfcUidPairingStore.TagKind.READ_ONLY
                }
                val isNew = NfcUidPairingStore.addPairedUidHex(this, uid, tagKind)
                if (isNew) {
                    showPairMetaPrompt(uid) {
                        finishWithOk(uidHex = uid, alreadyPaired = false)
                    }
                } else {
                    finishWithOk(uidHex = uid, alreadyPaired = true)
                }
                return@post
            }

            val uri = uriToWrite
            if (uri.isNullOrBlank()) {
                finishWithError(RESULT_FAILED_STR)
                return@post
            }

            val result = writeUriToTag(uri, tag)
            when (result) {
                WriteResult.OK -> {
                    if (shouldAutoPairOnWrite()) {
                        val uid = NfcTagUid.uidHex(tag)
                        if (uid != null) {
                            val isNew = NfcUidPairingStore.addPairedUidHex(
                                this,
                                uid,
                                NfcUidPairingStore.TagKind.WRITABLE
                            )
                            if (isNew) {
                                showPairMetaPrompt(uid) {
                                    finishWithOk(uidHex = uid, alreadyPaired = false)
                                }
                            } else {
                                finishWithOk(uidHex = uid, alreadyPaired = true)
                            }
                            return@post
                        }
                    }

                    finishWithOk(uidHex = null)
                }
                WriteResult.TOO_SMALL -> finishWithError(RESULT_TOO_SMALL_STR)
                WriteResult.NOT_WRITABLE -> finishWithError(RESULT_NOT_WRITABLE_STR)
                WriteResult.FAILED -> finishWithError(RESULT_FAILED_STR)
            }
        }
    }

    private fun safeDisableForegroundDispatch() {
        runCatching {
            nfcAdapter?.disableForegroundDispatch(this)
        }
    }

    private fun shouldAutoPairOnWrite(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(BlockingToggleKeys.KEY_AUTO_PAIR_ON_WRITE, false)
    }

    private fun showPairMetaPrompt(uid: String, onDone: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.dialog_paired_tag_pair_meta, null)
        v.findViewById<TextView>(R.id.tvUid).text = uid

        val etName = v.findViewById<TextInputEditText>(R.id.etTagName)
        val etNote = v.findViewById<TextInputEditText>(R.id.etTagNote)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.paired_tag_pair_prompt_title))
            .setMessage(getString(R.string.paired_tag_pair_prompt_message))
            .setView(v)
            .setPositiveButton(getString(R.string.paired_tag_pair_prompt_save)) { _, _ ->
                NfcUidPairingStore.setTagMeta(
                    this,
                    uid,
                    etName.text?.toString(),
                    etNote.text?.toString()
                )
                onDone()
            }
            .setNegativeButton(getString(R.string.paired_tag_pair_prompt_skip)) { _, _ ->
                onDone()
            }
            .setCancelable(false)
            .showAccented()
    }

    private fun finishWithOk(uidHex: String?, alreadyPaired: Boolean = false) {
        safeDisableForegroundDispatch()
        tvTitle.text = when {
            alreadyPaired -> getString(R.string.nfc_pair_already_added)
            uidHex != null -> getString(R.string.nfc_pair_ok)
            else -> getString(R.string.nfc_write_ok)
        }
        tvHint.text = when {
            uidHex != null && alreadyPaired -> getString(R.string.nfc_pair_ok_with_uid, uidHex)
            uidHex != null -> getString(R.string.nfc_pair_ok_with_uid, uidHex)
            else -> ""
        }
        progress.isIndeterminate = true

        Toast.makeText(
            this,
            getString(
                when {
                    alreadyPaired -> R.string.nfc_pair_already_added
                    uidHex != null -> R.string.nfc_pair_ok
                    else -> R.string.nfc_write_ok
                }
            ),
            Toast.LENGTH_SHORT
        ).show()

        handler.postDelayed({
            val data = Intent().apply {
                putExtra(EXTRA_RESULT, RESULT_OK_STR)
                if (uidHex != null) putExtra(EXTRA_UID, uidHex)
                putExtra(EXTRA_ALREADY_PAIRED, alreadyPaired)
            }
            setResult(RESULT_OK, data)
            finish()
        }, 500)
    }

    private fun isWritableCapable(tag: Tag): Boolean {
        val ndefTech = Ndef.get(tag)
        if (ndefTech != null) {
            return try {
                ndefTech.connect()
                ndefTech.isWritable
            } catch (_: Exception) {
                false
            } finally {
                try { ndefTech.close() } catch (_: Exception) {}
            }
        }
        return NdefFormatable.get(tag) != null
    }

    private fun finishWithError(result: String) {
        safeDisableForegroundDispatch()
        tvTitle.text = getString(R.string.nfc_write_error_generic)
        tvHint.text = ""

        progress.isIndeterminate = true

        Toast.makeText(this, getString(R.string.nfc_write_error_generic), Toast.LENGTH_SHORT).show()

        handler.postDelayed({
            val data = Intent().apply { putExtra(EXTRA_RESULT, result) }
            setResult(RESULT_CANCELED, data)
            finish()
        }, 600)
    }

    private fun writeUriToTag(uriString: String, tag: Tag): WriteResult {
        var ndef: Ndef? = null
        var formatable: NdefFormatable? = null

        return try {
            val record = NdefRecord.createUri(uriString)
            val message = NdefMessage(arrayOf(record))

            val ndefTech = Ndef.get(tag)
            if (ndefTech != null) {
                ndef = ndefTech
                ndef.connect()

                if (!ndef.isWritable) return WriteResult.NOT_WRITABLE
                if (ndef.maxSize < message.toByteArray().size) return WriteResult.TOO_SMALL

                ndef.writeNdefMessage(message)
                WriteResult.OK
            } else {
                val formatableTech = NdefFormatable.get(tag) ?: return WriteResult.FAILED
                formatable = formatableTech
                formatable.connect()
                formatable.format(message)
                WriteResult.OK
            }
        } catch (_: Exception) {
            WriteResult.FAILED
        } finally {
            try { ndef?.close() } catch (_: Exception) {}
            try { formatable?.close() } catch (_: Exception) {}
        }
    }
}
