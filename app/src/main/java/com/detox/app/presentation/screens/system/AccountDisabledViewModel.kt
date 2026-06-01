package com.detox.app.presentation.screens.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.FirestoreService
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Loads the ban reason shown on [AccountDisabledScreen]. */
@HiltViewModel
class AccountDisabledViewModel @Inject constructor(
    private val firestoreService: FirestoreService,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    init {
        firebaseAuth.currentUser?.uid?.let { uid ->
            viewModelScope.launch {
                _reason.value = firestoreService.getDisabledReason(uid)
            }
        }
    }
}
