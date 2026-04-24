package com.detox.app.presentation.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.service.DailyEvaluationWorker
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject

sealed interface HistoryItem {
    val sortDate: Long

    data class Solo(val entity: ChallengeEntity) : HistoryItem {
        override val sortDate: Long get() = entity.endDate
    }

    data class Group(
        val entity: GroupChallengeEntity,
        /** "won" | "eliminated" | "running" | "cancelled" */
        val myResult: String,
        val successCount: Int,
        val totalCount: Int,
    ) : HistoryItem {
        override val sortDate: Long get() = entity.endDate
    }
}

data class ProfileStats(
    val currentStreak: Int = 0,
    val challengesCompleted: Int = 0,
    val appsBlocked: Int = 0
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val database: DetoxDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val userEmail: String? = firebaseAuth.currentUser?.email
    val displayName: String? = firebaseAuth.currentUser?.displayName?.takeIf { it.isNotBlank() }
    val memberSinceMs: Long? = firebaseAuth.currentUser?.metadata?.creationTimestamp

    private val _stats = MutableStateFlow(ProfileStats())
    val stats: StateFlow<ProfileStats> = _stats.asStateFlow()

    private val _recentChallenges = MutableStateFlow<List<ChallengeEntity>>(emptyList())
    val recentChallenges: StateFlow<List<ChallengeEntity>> = _recentChallenges.asStateFlow()

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val _allHistoryItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val allHistoryItems: StateFlow<List<HistoryItem>> = _allHistoryItems.asStateFlow()

    val activeChallenges: StateFlow<List<ChallengeEntity>> =
        database.challengeDao().getActiveChallenges()
            .map { it.take(2) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val completedCount = database.challengeDao().getCompletedCount()
            val allChallenges = database.challengeDao().getAllChallengesList()
            val appsBlocked = allChallenges
                .flatMap { c ->
                    c.appPackageNames?.split(",")?.filter { it.isNotBlank() }
                        ?: listOf(c.appPackageName).filter { it.isNotBlank() }
                }
                .toSet().size
            val streak = computeStreak()
            _stats.value = ProfileStats(streak, completedCount, appsBlocked)
            _recentChallenges.value = database.challengeDao().getRecentFinishedChallenges()
            loadHistoryItems()
        }
    }

    private suspend fun loadHistoryItems() {
        val userId = firebaseAuth.currentUser?.uid

        val soloItems = database.challengeDao().getFinishedSoloChallenges()
            .map { HistoryItem.Solo(it) }

        val allGroupEntities = database.groupChallengeDao().getAllList()
        val groupItems = allGroupEntities.mapNotNull { entity ->
            val myStatus = userId?.let { getParticipantStatus(entity.participantsJson, it) }
            val successCount = countParticipantsByStatus(entity.participantsJson, "success")
            val totalCount = countParticipants(entity.participantsJson)

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

        val sorted = (soloItems + groupItems).sortedByDescending { it.sortDate }
        _allHistoryItems.value = sorted
        _historyItems.value = sorted.take(3)
    }

    private fun getParticipantStatus(participantsJson: String, userId: String): String? {
        return runCatching {
            val array = JSONArray(participantsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("userId") == userId) return obj.optString("status", null)
            }
            null
        }.getOrNull()
    }

    private fun countParticipantsByStatus(participantsJson: String, status: String): Int =
        runCatching {
            val array = JSONArray(participantsJson)
            (0 until array.length()).count { i ->
                array.getJSONObject(i).optString("status") == status
            }
        }.getOrDefault(0)

    private fun countParticipants(participantsJson: String): Int =
        runCatching { JSONArray(participantsJson).length() }.getOrDefault(0)

    private suspend fun computeStreak(): Int {
        val logs = database.dailyLogDao().getAllLogsOrderedByDateDesc()
        val byDate = logs.groupBy { it.date }
        val sortedDates = byDate.keys.sortedDescending()
        var streak = 0
        for (date in sortedDates) {
            val exceeded = byDate[date]?.any { it.limitExceeded } == true
            if (!exceeded) streak++ else break
        }
        return streak
    }

    fun runEvaluationNow() {
        Timber.d("Profile: manually triggering DailyEvaluationWorker")
        val request = OneTimeWorkRequestBuilder<DailyEvaluationWorker>()
            .addTag(TAG_MANUAL_EVALUATION)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        Timber.d("Profile: worker enqueued with id=${request.id}")
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            firebaseAuth.signOut()
            onComplete()
        }
    }

    companion object {
        const val TAG_MANUAL_EVALUATION = "manual_evaluation"
    }
}
