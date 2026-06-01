package com.detox.app.presentation.screens.support

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

/** Support ticket categories. The [firestoreValue] is what gets persisted; the
 *  label shown to the user is resolved from string resources in the screen. */
enum class SupportCategory(val firestoreValue: String) {
    BUG("bug"),
    QUESTION("question"),
    COMPLAINT("complaint"),
    PAYOUT("payout"),
    OTHER("other")
}

data class SupportFormState(
    val category: SupportCategory? = null,
    val subject: String = "",
    val message: String = "",
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val showValidationErrors: Boolean = false,
    val hasError: Boolean = false
) {
    val categoryValid: Boolean get() = category != null
    val subjectValid: Boolean get() = subject.isNotBlank()
    val messageValid: Boolean get() = message.trim().length >= 10
    val isValid: Boolean get() = categoryValid && subjectValid && messageValid
}

@HiltViewModel
class SupportViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _state = MutableStateFlow(SupportFormState())
    val state: StateFlow<SupportFormState> = _state.asStateFlow()

    fun onCategorySelected(category: SupportCategory) {
        _state.update { it.copy(category = category, hasError = false) }
    }

    fun onSubjectChanged(value: String) {
        _state.update { it.copy(subject = value, hasError = false) }
    }

    fun onMessageChanged(value: String) {
        _state.update { it.copy(message = value, hasError = false) }
    }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return

        if (!current.isValid) {
            _state.update { it.copy(showValidationErrors = true) }
            return
        }

        val user = firebaseAuth.currentUser
        if (user == null) {
            Timber.w("Support ticket submit aborted — no authenticated user")
            _state.update { it.copy(hasError = true) }
            return
        }

        _state.update { it.copy(isSubmitting = true, hasError = false) }

        viewModelScope.launch {
            // New document → use auto-ID .add() (NOT SetOptions.merge, this is not an upsert).
            val ticket = hashMapOf(
                "userId" to user.uid,
                "username" to (user.displayName ?: user.email?.substringBefore("@").orEmpty()),
                "email" to (user.email.orEmpty()),
                "category" to current.category!!.firestoreValue,
                "subject" to current.subject.trim(),
                "message" to current.message.trim(),
                "status" to "open",
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "createdAt" to System.currentTimeMillis(),
                "resolvedAt" to null
            )

            runCatching {
                firestore.collection("supportTickets").add(ticket).await()
            }.onSuccess {
                Timber.i("Support ticket created: ${it.id}")
                _state.update { s -> s.copy(isSubmitting = false, submitted = true) }
            }.onFailure { e ->
                Timber.e(e, "Failed to create support ticket")
                _state.update { s -> s.copy(isSubmitting = false, hasError = true) }
            }
        }
    }
}
