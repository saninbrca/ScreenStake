package com.detox.app.presentation.screens.username

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val PREFS_NAME = "detox_settings"
private const val KEY_USERNAME = "username"
private const val MIN_LENGTH = 3
private const val MAX_LENGTH = 20

enum class UsernameAvailability { Idle, Checking, Available, Taken, TooShort }

data class UsernameUiState(
    val usernameInput: String = "",
    val availability: UsernameAvailability = UsernameAvailability.Idle,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    /** True when the user already has a username — the screen should immediately complete. */
    val skip: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class UsernameSelectionViewModel @Inject constructor(
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(UsernameUiState())
    val state: StateFlow<UsernameUiState> = _state.asStateFlow()

    private val inputFlow = MutableStateFlow("")

    init {
        // If the user already has a username (e.g. returning user routed here defensively),
        // cache it and signal the screen to skip immediately.
        viewModelScope.launch {
            val uid = firebaseAuthService.currentUserId()
            if (uid != null) {
                val existing = firestoreService.getUsername(uid)
                if (existing != null) {
                    cacheUsername(existing)
                    _state.value = _state.value.copy(skip = true)
                    return@launch
                }
            }
            // Otherwise start the debounced availability checker.
            collectAvailability()
        }
    }

    private suspend fun collectAvailability() {
        inputFlow
            .debounce(500)
            .distinctUntilChanged()
            .collect { value ->
                if (value.length < MIN_LENGTH) {
                    _state.value = _state.value.copy(
                        availability = if (value.isEmpty()) UsernameAvailability.Idle
                        else UsernameAvailability.TooShort
                    )
                    return@collect
                }
                _state.value = _state.value.copy(availability = UsernameAvailability.Checking)
                val available = firestoreService.isUsernameAvailable(value)
                // Guard against a stale result if the input changed meanwhile.
                if (inputFlow.value != value) return@collect
                _state.value = _state.value.copy(
                    availability = if (available) UsernameAvailability.Available
                    else UsernameAvailability.Taken
                )
            }
    }

    fun onInputChange(raw: String) {
        val filtered = raw.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .take(MAX_LENGTH)
        _state.value = _state.value.copy(
            usernameInput = filtered,
            availability = if (filtered.isEmpty()) UsernameAvailability.Idle else _state.value.availability
        )
        inputFlow.value = filtered
    }

    fun save() {
        val current = _state.value
        if (current.isSaving) return
        val name = current.usernameInput.lowercase()
        if (name.length < MIN_LENGTH || current.availability != UsernameAvailability.Available) return
        _state.value = current.copy(isSaving = true)
        viewModelScope.launch {
            val uid = firebaseAuthService.currentUserId()
            if (uid == null) {
                _state.value = _state.value.copy(isSaving = false)
                return@launch
            }
            firestoreService.saveUsername(uid, name)
                .onSuccess {
                    firebaseAuthService.updateDisplayName(name)
                    cacheUsername(name)
                    _state.value = _state.value.copy(isSaving = false, saved = true)
                }
                .onFailure { e ->
                    if (e.message == "username_taken") {
                        _state.value = _state.value.copy(
                            isSaving = false,
                            availability = UsernameAvailability.Taken
                        )
                    } else {
                        Timber.e(e, "saveUsername failed")
                        _state.value = _state.value.copy(isSaving = false)
                    }
                }
        }
    }

    private fun cacheUsername(name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, name)
            .apply()
    }
}
