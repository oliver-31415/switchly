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
import android.widget.Toast
import at.saltyy.switchly.auth.AuthRuntime.AuthAction
import at.saltyy.switchly.R

/**
 * Simple facade for authentication.
 *
 * All real work is done in [AuthRuntime]. This keeps the rest of the app
 * decoupled from Firebase/Credential Manager details.
 */
object Auth {

    /**
     * Start Google sign-in using Credential Manager.
     */
    fun startSignIn(
        activity: Activity,
        onFinished: ((Boolean, String?) -> Unit)? = null
    ) {
        AuthRuntime.startSignIn(
            activity = activity,
            onResult = { success, error ->
                val msg = if (success) {
                    activity.getString(R.string.auth_sign_in_success)
                } else {
                    AuthRuntime.userFacingError(activity, error, AuthAction.GOOGLE_SIGN_IN)
                }
                Toast.makeText(activity, msg, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                onFinished?.invoke(success, error?.message)
            }
        )
    }

    fun signInWithEmail(
        context: Context,
        email: String,
        password: String,
        onFinished: ((Boolean, String?) -> Unit)? = null
    ) {
        AuthRuntime.signInWithEmail(context, email, password) { success, error ->
            val msg = if (success) {
                context.getString(R.string.auth_email_sign_in_success)
            } else {
                AuthRuntime.userFacingError(context, error, AuthAction.EMAIL_SIGN_IN)
            }
            Toast.makeText(context, msg, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            onFinished?.invoke(success, error?.message)
        }
    }

    fun createAccountWithEmail(
        context: Context,
        email: String,
        password: String,
        onFinished: ((Boolean, String?) -> Unit)? = null
    ) {
        AuthRuntime.createAccountWithEmail(context, email, password) { success, error ->
            val msg = if (success) {
                context.getString(R.string.auth_email_create_success)
            } else {
                AuthRuntime.userFacingError(context, error, AuthAction.CREATE_ACCOUNT)
            }
            Toast.makeText(context, msg, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            onFinished?.invoke(success, error?.message)
        }
    }

    fun sendPasswordResetEmail(
        context: Context,
        email: String,
        onFinished: ((Boolean, String?) -> Unit)? = null
    ) {
        AuthRuntime.sendPasswordResetEmail(context, email) { success, error ->
            val msg = if (success) {
                context.getString(R.string.auth_password_reset_email_sent)
            } else {
                AuthRuntime.userFacingError(context, error, AuthAction.RESET_PASSWORD)
            }
            Toast.makeText(context, msg, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            onFinished?.invoke(success, error?.message)
        }
    }

    /**
     * Sign the user out of Firebase.
     */
    fun signOut(context: Context, onDone: () -> Unit) {
        AuthRuntime.signOut(context, onDone)
    }

    /**
     * Returns the current Firebase UID (or null if not signed in).
     */
    fun uid(): String? = AuthRuntime.uid()
}
