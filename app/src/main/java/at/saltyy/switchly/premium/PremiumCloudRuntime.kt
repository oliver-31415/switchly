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
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.auth.Auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Syncs the user's premium state to Firestore.
 * Stored under:
 *   switchly_users/<uid>:
 *     hasPremium            : Boolean
 *     premiumLastSyncedAt   : Long (Unix timestamp in milliseconds)
 * Notes:
 * - This is only a mirror for cloud-based features or remote diagnostics.
 * - Play builds use local Play Billing; external-payment builds can restore a verified backend entitlement.
 */
object PremiumCloudRuntime {

    private const val TAG = "PremiumCloudRuntime"
    private const val COLLECTION = "switchly_users"
    private const val FIELD_HAS_PREMIUM = "hasPremium"
    private const val FIELD_HAS_PREMIUM_EXTERNAL = "hasPremiumExternal"

    /**
     * Sends the user's current premium state to Firestore.
     * Requirements:
     *  - User must be logged in (Auth.uid() != null)
     *  - Premium state is determined locally by PremiumManager
     */
    fun syncPremiumFlag(ctx: Context) {
        if (!BuildConfig.SWITCHLY_FIREBASE_ENABLED) return

        val uid = Auth.uid() ?: return
        val isPremium = PremiumManager.isPremium(ctx)

        val db = FirebaseFirestore.getInstance()
        val doc = db.collection(COLLECTION).document(uid)

        val data = mapOf(
            FIELD_HAS_PREMIUM to isPremium,
            "premiumLastSyncedAt" to System.currentTimeMillis()
        )

        doc.set(data, SetOptions.merge())
            .addOnSuccessListener {
                if (BuildConfig.DEBUG) Log.d(TAG, "syncPremiumFlag: hasPremium=$isPremium synced for $uid")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "syncPremiumFlag failed", e)
            }
    }

    /**
     * Reads external entitlement mirrored by your payment backend/webhook.
     * The webhook can set either hasPremiumExternal=true or hasPremium=true.
     */
    fun refreshExternalEntitlement(
        ctx: Context,
        onResult: (active: Boolean, error: Throwable?) -> Unit
    ) {
        if (!BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            onResult(false, IllegalStateException("Firebase disabled for this build"))
            return
        }

        val uid = Auth.uid()
        if (uid == null) {
            onResult(false, IllegalStateException("User is not signed in"))
            return
        }

        val db = FirebaseFirestore.getInstance()
        db.collection(COLLECTION).document(uid).get()
            .addOnSuccessListener { doc ->
                val active = doc.getBoolean(FIELD_HAS_PREMIUM_EXTERNAL)
                    ?: doc.getBoolean(FIELD_HAS_PREMIUM)
                    ?: false

                PremiumManager.setPremiumFromExternalVerified(ctx, active)
                onResult(active, null)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "refreshExternalEntitlement failed", error)
                onResult(false, error)
            }
    }
}
