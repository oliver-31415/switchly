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
import at.saltyy.switchly.ui.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

/**
 * Deletes the current Switchly account:
 * 1) Deletes Firestore user document and its "backups" subcollection.
 * 2) Attempts to delete the FirebaseAuth user (may require recent login).
 * 3) Clears local SharedPreferences.
 * 4) Restarts the app at MainActivity.
 */
object AccountDeletion {

    private const val TAG = "AccountDeletion"
    private const val COLLECTION_USERS = "switchly_users"
    private const val SUB_BACKUPS = "backups"

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
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.account_delete_recent_login_required),
                                Toast.LENGTH_LONG
                            ).show()
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
                if (snapshot.isEmpty) {
                    onDone(true, null)
                    return@addOnSuccessListener
                }

                val batch = db.batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit()
                    .addOnSuccessListener { onDone(true, null) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to delete backups", e)
                        onDone(false, e)
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to fetch backups", e)
                onDone(false, e)
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
        // Default SharedPreferences (PreferenceFragmentCompat)
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        defaultPrefs.edit { clear() }

        // App-specific prefs
        val internalPrefs = ctx.getSharedPreferences("switchly_prefs", Context.MODE_PRIVATE)
        internalPrefs.edit { clear() }
    }

    private fun restartApp(activity: Activity) {
        val intent = Intent(activity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
