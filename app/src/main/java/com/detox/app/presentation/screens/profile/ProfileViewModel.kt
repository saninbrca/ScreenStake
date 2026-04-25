package com.detox.app.presentation.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.service.DailyEvaluationWorker
import com.detox.app.service.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
import kotlinx.coroutines.tasks.await
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

sealed interface PayoutState {
    object Loading : PayoutState
    object NotConnected : PayoutState
    object OnboardingIncomplete : PayoutState
    object Active : PayoutState
}

sealed interface PayoutClaimState {
    object Idle : PayoutClaimState
    object Loading : PayoutClaimState
    data class Success(val transferredCents: Int) : PayoutClaimState
    data class Error(val message: String) : PayoutClaimState
}

data class IbanData(val iban: String, val name: String)

sealed interface IbanSaveState {
    object Idle : IbanSaveState
    object Loading : IbanSaveState
    object Success : IbanSaveState
    data class Error(val message: String) : IbanSaveState
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val database: DetoxDatabase,
    private val cloudFunctionsService: CloudFunctionsService,
    private val firestore: FirebaseFirestore,
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

    private val _payoutState = MutableStateFlow<PayoutState>(PayoutState.Loading)
    val payoutState: StateFlow<PayoutState> = _payoutState.asStateFlow()

    private val _pendingPayoutCents = MutableStateFlow(0)
    val pendingPayoutCents: StateFlow<Int> = _pendingPayoutCents.asStateFlow()

    private val _payoutClaimState = MutableStateFlow<PayoutClaimState>(PayoutClaimState.Idle)
    val payoutClaimState: StateFlow<PayoutClaimState> = _payoutClaimState.asStateFlow()

    private val _ibanData = MutableStateFlow<IbanData?>(null)
    val ibanData: StateFlow<IbanData?> = _ibanData.asStateFlow()

    private val _ibanSaveState = MutableStateFlow<IbanSaveState>(IbanSaveState.Idle)
    val ibanSaveState: StateFlow<IbanSaveState> = _ibanSaveState.asStateFlow()

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
        viewModelScope.launch { refreshPayoutState() }
        viewModelScope.launch { fetchPendingPayouts() }
        viewModelScope.launch { fetchIban() }
    }

    private suspend fun fetchIban() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        runCatching {
            firestore.collection("users").document(uid).get().await()
        }.onSuccess { doc ->
            val iban = doc.getString("payoutIban")?.takeIf { it.isNotBlank() } ?: return@onSuccess
            val name = doc.getString("payoutName") ?: ""
            _ibanData.value = IbanData(iban, name)
        }
    }

    fun saveIban(iban: String, name: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        if (_ibanSaveState.value is IbanSaveState.Loading) return
        _ibanSaveState.value = IbanSaveState.Loading
        viewModelScope.launch {
            runCatching {
                firestore.collection("users").document(uid)
                    .set(
                        mapOf("payoutIban" to iban.trim(), "payoutName" to name.trim()),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
            }.onSuccess {
                _ibanData.value = IbanData(iban.trim(), name.trim())
                _ibanSaveState.value = IbanSaveState.Success
            }.onFailure { e ->
                _ibanSaveState.value = IbanSaveState.Error(e.message ?: "Fehler beim Speichern")
            }
        }
    }

    fun clearIbanSaveState() { _ibanSaveState.value = IbanSaveState.Idle }

    private suspend fun refreshPayoutState() {
        cloudFunctionsService.getConnectedAccountStatus()
            .onSuccess { status ->
                _payoutState.value = when {
                    !status.hasAccount -> PayoutState.NotConnected
                    status.chargesEnabled && status.payoutsEnabled -> PayoutState.Active
                    else -> PayoutState.OnboardingIncomplete
                }
                Timber.d(
                    "Payout UI: connectedAccount=%s pending=%d",
                    status.hasAccount,
                    _pendingPayoutCents.value
                )
            }
            .onFailure { _payoutState.value = PayoutState.NotConnected }
    }

    private suspend fun fetchPendingPayouts() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        runCatching {
            val snap = firestore.collection("users").document(uid)
                .collection("pendingPayouts").get().await()
            snap.documents.sumOf { (it.getLong("amount") ?: 0L).toInt() }
        }.onSuccess { total ->
            _pendingPayoutCents.value = total
            Timber.d("Payout UI: pending=%d cents", total)
        }
    }

    fun refreshOnResume() {
        viewModelScope.launch {
            refreshPayoutState()
            fetchPendingPayouts()
        }
    }

    fun startOnboarding(onUrl: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            cloudFunctionsService.createConnectedAccount()
                .onSuccess { url ->
                    onUrl(url)
                    refreshPayoutState()
                }
                .onFailure { onError(it.message ?: "Unknown error") }
        }
    }

    fun claimPendingPayouts() {
        if (_payoutClaimState.value is PayoutClaimState.Loading) return
        _payoutClaimState.value = PayoutClaimState.Loading
        viewModelScope.launch {
            cloudFunctionsService.claimPendingPayouts()
                .onSuccess { result ->
                    if (result.transferred > 0) {
                        NotificationHelper.sendPayoutReceived(context, result.transferred)
                    }
                    _pendingPayoutCents.value = 0
                    _payoutClaimState.value = PayoutClaimState.Success(result.transferred)
                    refreshPayoutState()
                }
                .onFailure { e ->
                    _payoutClaimState.value = PayoutClaimState.Error(e.message ?: "Fehler")
                }
        }
    }

    fun clearPayoutClaimState() { _payoutClaimState.value = PayoutClaimState.Idle }

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
