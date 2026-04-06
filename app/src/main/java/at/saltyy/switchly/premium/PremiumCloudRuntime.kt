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

package at.saltyy.switchly.premium

import android.content.Context
import android.util.Log
import at.saltyy.switchly.auth.Auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Syncs the user's premium state to Firestore.
 *
 * Stored under:
 *   switchly_users/<uid>:
 *     hasPremium            : Boolean
 *     premiumLastSyncedAt   : Long (Unix timestamp in milliseconds)
 *
 * Notes:
 * - This is only a mirror for cloud-based features or remote diagnostics.
 * - The authoritative premium state always comes from local Play Billing via PremiumManager.
 */
object PremiumCloudRuntime {

    private const val TAG = "PremiumCloudRuntime"
    private const val COLLECTION = "switchly_users"

    /**
     * Sends the user's current premium state to Firestore.
     *
     * Requirements:
     *  - User must be logged in (Auth.uid() != null)
     *  - Premium state is determined locally by PremiumManager
     */
    fun syncPremiumFlag(ctx: Context) {
        val uid = Auth.uid() ?: return
        val isPremium = PremiumManager.isPremium(ctx)

        val db = FirebaseFirestore.getInstance()
        val doc = db.collection(COLLECTION).document(uid)

        val data = mapOf(
            "hasPremium" to isPremium,
            "premiumLastSyncedAt" to System.currentTimeMillis()
        )

        doc.set(data, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "syncPremiumFlag: hasPremium=$isPremium synced for $uid")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "syncPremiumFlag failed", e)
            }
    }
}
