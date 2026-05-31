package com.detox.app.presentation.screens.challenge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.domain.repository.ChallengeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HardModeFailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val challengeRepository: ChallengeRepository,
) : ViewModel() {

    private val challengeId: String = savedStateHandle.get<String>("challengeId") ?: ""

    private val _amountCents = MutableStateFlow<Int?>(null)
    val amountCents: StateFlow<Int?> = _amountCents.asStateFlow()

    init {
        viewModelScope.launch {
            challengeRepository.getChallengeById(challengeId)
                .onSuccess { _amountCents.value = it?.amountCents }
                .onFailure { Timber.e(it, "HardModeFail: failed to load challenge $challengeId") }
        }
    }
}
