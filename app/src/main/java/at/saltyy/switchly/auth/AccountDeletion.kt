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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ActivityHistoryLogStore
import at.saltyy.switchly.data.statistics.StatsPersistence
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

/**
 * Deletes the current Switchly account:
 * 1) Deletes Firestore user document and its "backups" subcollection.
 * 2) Attempts to delete the FirebaseAuth user (may require recent login).
 * 3) Clears local preferences and databases.
 * 4) Restarts the app at MainActivity.
 */
object AccountDeletion {

    private const val TAG = "AccountDeletion"
    private const val COLLECTION_USERS = "switchly_users"
    private const val SUB_BACKUPS = "backups"
    private const val SUB_STATS_CHUNKS = "stats_chunks"

    fun deleteAccount(activity: Activity) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(
                activity,
                activity.getString(R.string.settings_google_logged_out),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val uid = user.uid
        val db = FirebaseFirestore.getInstance()

        // Delete backups first (if any)
        deleteBackups(db, uid) { backupsOk, backupsErr ->
            if (!backupsOk) {
                val errText = backupsErr?.localizedMessage ?: activity.getString(R.string.error_unknown)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.account_delete_failed_delete_backups_fmt, errText),
                    Toast.LENGTH_LONG
                ).show()
                return@deleteBackups
            }

            // Delete main user document
            deleteUserDocument(db, uid) { docOk, docErr ->
                if (!docOk) {
                    val errText = docErr?.localizedMessage ?: activity.getString(R.string.error_unknown)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.account_delete_failed_delete_cloud_data_fmt, errText),
                        Toast.LENGTH_LONG
                    ).show()
                    return@deleteUserDocument
                }

                // Try to delete Firebase auth user
                deleteAuthUser(user) { authOk, authErr ->
                    if (!authOk) {
                        // If this is the "recent login required" case, show a nicer message
                        if (authErr is FirebaseAuthRecentLoginRequiredException) {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.account_delete_recent_login_title)
                                .setMessage(R.string.account_delete_recent_login_required)
                                .setPositiveButton(R.string.ok, null)
                                .showAccented()
                        } else {
                            val errText = authErr?.localizedMessage ?: activity.getString(R.string.error_unknown)
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.account_delete_failed_delete_account_fmt, errText),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@deleteAuthUser
                    }

                    // Clear local data
                    clearLocalData(activity)

                    Toast.makeText(activity, activity.getString(R.string.account_deleted), Toast.LENGTH_SHORT).show()

                    // Restart app to MainActivity
                    restartApp(activity)
                }
            }
        }
    }

    private fun deleteBackups(
        db: FirebaseFirestore,
        uid: String,
        onDone: (Boolean, Exception?) -> Unit
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
                chunks.documents.forEach { chunk ->
                    batch.delete(chunk.reference)
                }
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
        onDone: (Boolean, Exception?) -> Unit
    ) {
        db.collection(COLLECTION_USERS)
            .document(uid)
            .delete()
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to delete user document", e)
                onDone(false, e)
            }
    }

    private fun deleteAuthUser(
        user: com.google.firebase.auth.FirebaseUser,
        onDone: (Boolean, Exception?) -> Unit
    ) {
        user.delete()
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to delete auth user", e)
                onDone(false, e)
            }
    }

    private fun clearLocalData(ctx: Context) {
        StatsPersistence.prepareForFullDataDeletion(ctx)
        try {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit(commit = true) { clear() }
            ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE).edit(commit = true) { clear() }
            ctx.getSharedPreferences("switchly_prefs_schedules", Context.MODE_PRIVATE).edit(commit = true) { clear() }
            ctx.getSharedPreferences("switchly_ui_hints", Context.MODE_PRIVATE).edit(commit = true) { clear() }
            ctx.getSharedPreferences(ActivityHistoryLogStore.PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            ctx.databaseList().forEach { databaseName ->
                ctx.deleteDatabase(databaseName)
            }
        } finally {
            StatsPersistence.resumeAfterFullDataDeletion(ctx)
        }
    }

    private fun restartApp(activity: Activity) {
        val intent = Intent(activity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
