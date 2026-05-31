package com.detox.app.data.remote.firebase

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthService @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {

    /**
     * Sign in with a Google ID token obtained from the Google Sign-In flow.
     * Returns the authenticated FirebaseUser on success.
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            val user = result.user ?: return Result.failure(Exception("Sign-in succeeded but user is null"))
            Timber.d("Signed in with Google as ${user.displayName} (${user.uid})")
            Result.success(user)
        } catch (e: Exception) {
            Timber.e(e, "Google sign-in failed")
            Result.failure(e)
        }
    }

    /**
     * Sign in an existing user with email and password.
     * Returns the authenticated FirebaseUser on success, or a descriptive error.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: return Result.failure(Exception("Sign-in succeeded but user is null"))
            Timber.d("Signed in with email as ${user.email} (${user.uid})")
            Result.success(user)
        } catch (e: Exception) {
            Timber.e(e, "Email sign-in failed")
            Result.failure(e)
        }
    }

    /**
     * Register a new user with email and password.
     * Returns the newly created FirebaseUser on success.
     */
    suspend fun registerWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: return Result.failure(Exception("Registration succeeded but user is null"))
            Timber.d("Registered new user ${user.email} (${user.uid})")
            Result.success(user)
        } catch (e: Exception) {
            Timber.e(e, "Email registration failed")
            Result.failure(e)
        }
    }

    /**
     * Sends a password-reset email to the given address.
     * Returns [Result.success] even if the email is not registered (to avoid user enumeration).
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Timber.d("Password reset email sent to $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send password reset to $email")
            Result.failure(e)
        }
    }

    /**
     * Permanently deletes the currently signed-in Firebase Auth account.
     * The caller is responsible for deleting all associated Firestore data first.
     */
    suspend fun deleteAccount(): Result<Unit> {
        return try {
            firebaseAuth.currentUser?.delete()?.await()
                ?: return Result.failure(Exception("No signed-in user to delete"))
            Timber.d("Firebase Auth account deleted")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete Firebase Auth account")
            Result.failure(e)
        }
    }

    /**
     * Sends a verification email to the currently signed-in user.
     */
    suspend fun sendEmailVerification(): Result<Unit> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(Exception("No signed-in user"))
            user.sendEmailVerification().await()
            Timber.d("Verification email sent to ${user.email}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send verification email")
            Result.failure(e)
        }
    }

    /**
     * Reloads the current user from the Firebase backend and returns the
     * latest email-verification state. Returns false if no user is signed in.
     */
    suspend fun reloadAndCheckEmailVerified(): Result<Boolean> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(Exception("No signed-in user"))
            user.reload().await()
            val verified = firebaseAuth.currentUser?.isEmailVerified == true
            Timber.d("Reloaded user — emailVerified=$verified")
            Result.success(verified)
        } catch (e: Exception) {
            Timber.e(e, "Failed to reload user")
            Result.failure(e)
        }
    }

    /** Returns the cached email-verification state without a network reload. */
    fun isEmailVerified(): Boolean = firebaseAuth.currentUser?.isEmailVerified == true

    /**
     * Re-authenticates the current user with their email + password.
     * Required by Firebase before sensitive operations such as account deletion.
     */
    suspend fun reauthenticateWithPassword(password: String): Result<Unit> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(Exception("No signed-in user"))
            val email = user.email
                ?: return Result.failure(Exception("Current user has no email"))
            val credential = EmailAuthProvider.getCredential(email, password)
            user.reauthenticate(credential).await()
            Timber.d("Re-authenticated user ${user.email}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Re-authentication failed")
            Result.failure(e)
        }
    }

    /**
     * Updates the current user's Auth profile displayName. Mirrors the chosen username onto
     * the FirebaseUser so every flow that reads currentUser()?.displayName (group create/join,
     * taunts) automatically carries the username.
     */
    suspend fun updateDisplayName(name: String): Result<Unit> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(Exception("No signed-in user"))
            user.updateProfile(userProfileChangeRequest { displayName = name }).await()
            Timber.d("Updated displayName to '$name' for uid=${user.uid}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update displayName")
            Result.failure(e)
        }
    }

    /** Sign the current user out. */
    fun signOut() {
        firebaseAuth.signOut()
        Timber.d("User signed out")
    }

    /** Returns true if a user is currently authenticated. */
    fun isSignedIn(): Boolean = firebaseAuth.currentUser != null

    /** Returns the UID of the currently signed-in user, or null. */
    fun currentUserId(): String? = firebaseAuth.currentUser?.uid

    /** Returns the current FirebaseUser, or null if not signed in. */
    fun currentUser(): FirebaseUser? = firebaseAuth.currentUser

    /**
     * Logs the current auth state at DEBUG level.
     * Call this at any point where a Firestore/Cloud Function operation is about to run
     * to verify the session is active before the SDK attaches an auth token.
     */
    fun logAuthState(tag: String = "AuthState") {
        val user = firebaseAuth.currentUser
        if (user == null) {
            Timber.tag(tag).w("NOT signed in — no Firebase user session")
        } else {
            Timber.tag(tag).d("Signed in: uid=%s email=%s",
                user.uid,
                user.email
            )
        }
    }
}
