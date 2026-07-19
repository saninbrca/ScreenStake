package com.detox.app.util

import android.content.Context
import com.detox.app.R
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.net.SocketTimeoutException
import timber.log.Timber

/**
 * Converts backend failures into safe, localized UI copy.
 *
 * Keep the original Throwable in Timber/Sentry at the call site. In particular, never expose
 * Firebase, Firestore, Stripe, or HTTP error details to the user: those messages are not
 * localized and may contain implementation details.
 */
object ErrorMessages {
    fun from(context: Context, error: Throwable, fallback: Int = R.string.error_generic): String {
        Timber.e(error, "User-facing error mapped to a localized message")
        val resource = when (error) {
            is FirebaseNetworkException, is IOException, is SocketTimeoutException -> R.string.error_network
            is FirebaseAuthException -> R.string.error_authentication
            is FirebaseFirestoreException -> when (error.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> R.string.error_permission_denied
                FirebaseFirestoreException.Code.NOT_FOUND -> R.string.error_not_found
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> R.string.error_network
                else -> fallback
            }
            else -> when {
                error.message.orEmpty().contains("stripe", ignoreCase = true) ||
                    error.message.orEmpty().contains("payment", ignoreCase = true) -> R.string.error_payment
                else -> fallback
            }
        }
        return context.getString(resource)
    }
}
