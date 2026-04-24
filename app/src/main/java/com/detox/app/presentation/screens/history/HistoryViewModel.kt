package com.detox.app.presentation.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.presentation.screens.profile.HistoryItem
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val database: DetoxDatabase,
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            load()
        }
    }

    private suspend fun load() {
        val userId = firebaseAuth.currentUser?.uid

        val soloItems = database.challengeDao().getFinishedSoloChallenges()
            .map { HistoryItem.Solo(it) }

        val groupItems = database.groupChallengeDao().getAllList().mapNotNull { entity ->
            val myStatus = userId?.let { getParticipantStatus(entity.participantsJson, it) }
            val successCount = countByStatus(entity.participantsJson, "success")
            val totalCount = runCatching { JSONArray(entity.participantsJson).length() }.getOrDefault(0)

            when {
                entity.status == "cancelled" ->
                    HistoryItem.Group(entity, "cancelled", successCount, totalCount)
                entity.status == "completed" -> {
                    val result = if (myStatus == "failed") "eliminated" else "won"
                    HistoryItem.Group(entity, result, successCount, totalCount)
                }
                entity.status == "active" && myStatus == "failed" ->
                    HistoryItem.Group(entity, "eliminated", successCount, totalCount)
                else -> null
            }
        }

        _historyItems.value = (soloItems + groupItems).sortedByDescending { it.sortDate }
        Timber.d("HistoryViewModel: loaded ${_historyItems.value.size} history items")
    }

    private fun getParticipantStatus(participantsJson: String, userId: String): String? =
        runCatching {
            val array = JSONArray(participantsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("userId") == userId) return obj.optString("status", null)
            }
            null
        }.getOrNull()

    private fun countByStatus(participantsJson: String, status: String): Int =
        runCatching {
            val array = JSONArray(participantsJson)
            (0 until array.length()).count { i ->
                array.getJSONObject(i).optString("status") == status
            }
        }.getOrDefault(0)
}
