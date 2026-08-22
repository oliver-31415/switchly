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

package at.saltyy.switchly.auth

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.saltyy.switchly.data.prefs.AppLogStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

/**
 * Self-service account deletion for the current authenticated Switchly user.
 * The UI re-authenticates the user before this starts so Firestore can be removed while the authenticated session is still valid.
 * Local profiles, rules, schedules and statistics are kept; only local account/cloud-session metadata is cleared after a successful deletion.
 */
object AccountDeletion {

    private const val TAG = "AccountDeletion"
    private const val COLLECTION_USERS = "switchly_users"
    private const val SUB_BACKUPS = "backups"
    private const val SUB_STATS_CHUNKS = "stats_chunks"

    enum class Stage {
        BACKUPS,
        CLOUD_DATA,
        AUTH_ACCOUNT,
    }

    data class Result(
        val success: Boolean,
        val stage: Stage? = null,
        val error: Throwable? = null,
    )

    fun deleteAccount(
        context: Context,
        onDone: (Result) -> Unit,
    ) {
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (user == null) {
            onDone(Result(success = false, stage = Stage.AUTH_ACCOUNT, error = IllegalStateException("Not signed in")))
            return
        }

        val appContext = context.applicationContext
        val uid = user.uid
        val db = FirebaseFirestore.getInstance()
        AppLogStore.append(appContext, TAG, "Starting self-service account deletion")

        deleteBackups(db, uid) { backupsOk, backupsErr ->
            if (!backupsOk) {
                AppLogStore.append(appContext, TAG, "Account deletion stopped while deleting backups", backupsErr)
                onDone(Result(success = false, stage = Stage.BACKUPS, error = backupsErr))
                return@deleteBackups
            }

            deleteUserDocument(db, uid) { docOk, docErr ->
                if (!docOk) {
                    AppLogStore.append(appContext, TAG, "Account deletion stopped while deleting cloud data", docErr)
                    onDone(Result(success = false, stage = Stage.CLOUD_DATA, error = docErr))
                    return@deleteUserDocument
                }

                user.delete()
                    .addOnSuccessListener {
                        clearLocalAccountState(appContext)
                        AppLogStore.append(appContext, TAG, "Firebase account and cloud data deleted")

                        // Firebase signs the user out after delete(). Also clear Credential Managers provider state so the next sign-in starts from a clean account session.
                        AuthRuntime.signOut(appContext) {
                            onDone(Result(success = true))
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Failed to delete auth user after cloud cleanup", error)
                        AppLogStore.append(
                            appContext,
                            TAG,
                            "Cloud data removed, but Firebase Auth account deletion failed; retry is safe",
                            error,
                        )
                        onDone(Result(success = false, stage = Stage.AUTH_ACCOUNT, error = error))
                    }
            }
        }
    }

    private fun deleteBackups(
        db: FirebaseFirestore,
        uid: String,
        onDone: (Boolean, Exception?) -> Unit,
    ) {
        db.collection(COLLECTION_USERS)
            .document(uid)
            .collection(SUB_BACKUPS)
            .get()
            .addOnSuccessListener { snapshot: QuerySnapshot ->
                deleteBackupDocumentsSequentially(
                    db = db,
                    documents = snapshot.documents,
                    index = 0,
                    onDone = onDone,
                )
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to fetch backups", error)
                onDone(false, error)
            }
    }

    private fun deleteBackupDocumentsSequentially(
        db: FirebaseFirestore,
        documents: List<DocumentSnapshot>,
        index: Int,
        onDone: (Boolean, Exception?) -> Unit,
    ) {
        if (index >= documents.size) {
            onDone(true, null)
            return
        }

        val backup = documents[index]
        backup.reference.collection(SUB_STATS_CHUNKS)
            .get()
            .addOnSuccessListener { chunks ->
                val batch = db.batch()
                chunks.documents.forEach { chunk -> batch.delete(chunk.reference) }
                batch.delete(backup.reference)
                batch.commit()
                    .addOnSuccessListener {
                        deleteBackupDocumentsSequentially(db, documents, index + 1, onDone)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Failed to delete backup data", error)
                        onDone(false, error)
                    }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to fetch statistics backup chunks", error)
                onDone(false, error)
            }
    }

    private fun deleteUserDocument(
        db: FirebaseFirestore,
        uid: String,
        onDone: (Boolean, Exception?) -> Unit,
    ) {
        db.collection(COLLECTION_USERS)
            .document(uid)
            .delete()
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to delete user document", error)
                onDone(false, error)
            }
    }

    private fun clearLocalAccountState(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
            remove("pref_last_backup_epoch_ms")
        }
    }
}
