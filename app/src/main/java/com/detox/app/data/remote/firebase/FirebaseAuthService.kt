package com.detox.app.data.remote.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
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
            Timber.d("Signed in as ${user.displayName} (${user.uid})")
            Result.success(user)
        } catch (e: Exception) {
            Timber.e(e, "Google sign-in failed")
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
}
