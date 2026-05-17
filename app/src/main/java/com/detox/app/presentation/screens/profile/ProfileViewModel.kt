package com.detox.app.presentation.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.detox.app.BuildConfig
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.local.db.entity.DailyLogEntity
import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.domain.repository.PaymentRepository
import com.detox.app.service.DailyEvaluationWorker
import com.detox.app.service.NotificationHelper
import com.detox.app.util.DateUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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

sealed interface IbanSetupState {
    object Idle : IbanSetupState
    object Loading : IbanSetupState
    object Success : IbanSetupState
    data class Error(val message: String) : IbanSetupState
}

data class PayoutChallengeInfo(
    val challengeTitle: String,
    val durationDays: Int,
    val isGroup: Boolean,
    val isRedemption: Boolean = false,
    val nobodyFailed: Boolean = false,
    val stakeRefundCents: Int,
    val prizeShareCents: Int,
    val appFeeCents: Int,
    val winnersCount: Int,
    val payoutStatus: String,
    val groupId: String?,
    val endDateMs: Long = 0L
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val database: DetoxDatabase,
    private val cloudFunctionsService: CloudFunctionsService,
    private val paymentRepository: PaymentRepository,
    private val firebaseAuthService: FirebaseAuthService,
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

    private val _ibanSetupState = MutableStateFlow<IbanSetupState>(IbanSetupState.Idle)
    val ibanSetupState: StateFlow<IbanSetupState> = _ibanSetupState.asStateFlow()

    private val _completedPayouts = MutableStateFlow<List<PayoutChallengeInfo>>(emptyList())
    val completedPayouts: StateFlow<List<PayoutChallengeInfo>> = _completedPayouts.asStateFlow()

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
        viewModelScope.launch { fetchCompletedPayouts() }
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
            fetchCompletedPayouts()
        }
    }

    fun setupPayoutAccount(iban: String, accountHolderName: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        if (_ibanSetupState.value is IbanSetupState.Loading) return
        _ibanSetupState.value = IbanSetupState.Loading
        viewModelScope.launch {
            paymentRepository.setupPayoutAccount(iban.trim(), accountHolderName.trim(), uid)
                .onSuccess {
                    _ibanSetupState.value = IbanSetupState.Success
                    _ibanData.value = IbanData(iban.trim(), accountHolderName.trim())
                    fetchPendingPayouts()
                    fetchCompletedPayouts()
                }
                .onFailure { e ->
                    _ibanSetupState.value = IbanSetupState.Error(e.message ?: "Fehler beim Speichern")
                }
        }
    }

    fun clearIbanSetupState() { _ibanSetupState.value = IbanSetupState.Idle }

    private suspend fun fetchCompletedPayouts() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val payouts = mutableListOf<PayoutChallengeInfo>()

        // Solo Hard Mode completed challenges (non-redemption)
        runCatching {
            database.challengeDao().getFinishedSoloChallenges()
                .filter { it.status == "completed" && it.mode == "hard" && it.amountCents != null && it.amountCents > 0 && it.isRedemption == 0 }
                .forEach { entity ->
                    val durationDays = if (entity.startDate > 0L && entity.endDate > entity.startDate) {
                        ((entity.endDate - entity.startDate) / 86_400_000L).toInt()
                    } else 0
                    val originalAmount = entity.amountCents ?: 0
                    val stakeRefund = (originalAmount * 0.80).toInt()
                    val appFee = originalAmount - stakeRefund
                    payouts += PayoutChallengeInfo(
                        challengeTitle = entity.appDisplayName ?: entity.appPackageName ?: "App",
                        durationDays = durationDays,
                        isGroup = false,
                        isRedemption = false,
                        stakeRefundCents = stakeRefund,
                        prizeShareCents = 0,
                        appFeeCents = appFee,
                        winnersCount = 0,
                        payoutStatus = "refunded",
                        groupId = null,
                        endDateMs = entity.endDate
                    )
                }
        }

        // Redemption Challenge wins
        runCatching {
            database.challengeDao().getFinishedSoloChallenges()
                .filter { it.status == "completed" && it.isRedemption != 0 && it.refundAmountCents != null }
                .forEach { entity ->
                    val refundCents = entity.refundAmountCents ?: 0
                    // Look up original challenge to compute fee accurately
                    val originalAmount = entity.originalChallengeId
                        ?.let { database.challengeDao().getChallengeById(it)?.amountCents } ?: 0
                    val appFee = if (originalAmount > 0) originalAmount - refundCents else 0
                    val durationDays = if (entity.startDate > 0L && entity.endDate > entity.startDate) {
                        ((entity.endDate - entity.startDate) / 86_400_000L).toInt()
                    } else 0
                    payouts += PayoutChallengeInfo(
                        challengeTitle = entity.appDisplayName ?: entity.appPackageName ?: "App",
                        durationDays = durationDays,
                        isGroup = false,
                        isRedemption = true,
                        stakeRefundCents = refundCents,
                        prizeShareCents = 0,
                        appFeeCents = appFee,
                        winnersCount = 0,
                        payoutStatus = "refunded",
                        groupId = null,
                        endDateMs = entity.endDate
                    )
                }
        }

        // Group challenges where user was a winner
        runCatching {
            val allGroup = database.groupChallengeDao().getAllList()
            allGroup.filter { it.status == "completed" }.forEach { entity ->
                val myStatus = getParticipantStatus(entity.participantsJson, uid)
                if (myStatus == "success" || myStatus == "active" || myStatus == "completed") {
                    // Fetch Firestore doc for prize breakdown and nobodyFailed flag
                    val groupDoc = runCatching {
                        firestore.collection("groupChallenges").document(entity.groupId).get().await()
                    }.getOrNull()
                    val prizePerWinner = ((groupDoc?.get("prizePerWinner") as? Long)?.toInt()
                        ?: (groupDoc?.get("perWinnerBonus") as? Long)?.toInt()) ?: 0
                    val appFee = (groupDoc?.get("appFee") as? Long)?.toInt() ?: 0
                    val nobodyFailed = groupDoc?.getBoolean("nobodyFailed") ?: false

                    // Check if prize is pending (no connected account)
                    val pendingSnap = runCatching {
                        firestore.collection("users").document(uid)
                            .collection("pendingPayouts")
                            .whereEqualTo("groupId", entity.groupId)
                            .get().await()
                    }.getOrNull()
                    val hasPendingPrize = (pendingSnap?.isEmpty == false)
                    val payoutStatus = if (hasPendingPrize) "pending_payout" else "refunded"

                    // Count winners from participants JSON
                    val winnersCount = countParticipantsByStatus(entity.participantsJson, "success")
                        .takeIf { it > 0 } ?: countParticipantsByStatus(entity.participantsJson, "active")

                    // Stake refund: 100% if nobody failed, 80% if someone failed
                    val stakeRefund = if (nobodyFailed) {
                        entity.buyInCents
                    } else {
                        (entity.buyInCents * 0.80).toInt()
                    }
                    val stakeAppFee = if (nobodyFailed) 0 else entity.buyInCents - stakeRefund

                    payouts += PayoutChallengeInfo(
                        challengeTitle = entity.appDisplayName ?: "App",
                        durationDays = entity.durationDays,
                        isGroup = true,
                        nobodyFailed = nobodyFailed,
                        stakeRefundCents = stakeRefund,
                        prizeShareCents = prizePerWinner,
                        appFeeCents = stakeAppFee,
                        winnersCount = winnersCount,
                        payoutStatus = payoutStatus,
                        groupId = entity.groupId,
                        endDateMs = entity.endDate
                    )
                }
            }
        }

        _completedPayouts.value = payouts.sortedByDescending { it.stakeRefundCents + it.prizeShareCents }
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

    // ── Debug-only state and functions ────────────────────────────────────────

    val debugActiveGroupChallenges: StateFlow<List<GroupChallengeEntity>> =
        database.groupChallengeDao().getAll()
            .map { list -> list.filter { it.status in listOf("active", "waiting") } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _debugDailyLogs = MutableStateFlow<List<DailyLogEntity>>(emptyList())
    val debugDailyLogs: StateFlow<List<DailyLogEntity>> = _debugDailyLogs.asStateFlow()

    private val _debugActiveChallenges = MutableStateFlow<List<ChallengeEntity>>(emptyList())
    val debugActiveChallenges: StateFlow<List<ChallengeEntity>> = _debugActiveChallenges.asStateFlow()

    fun debugSetAllChallengesEndNow() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            val newEnd = System.currentTimeMillis() + 5_000L
            database.challengeDao().getActiveChallengesList().forEach { challenge ->
                database.challengeDao().updateEndDate(challenge.id, newEnd)
            }
            val uid = firebaseAuth.currentUser?.uid
            database.groupChallengeDao().getByStatus(listOf("active", "waiting")).forEach { gc ->
                database.groupChallengeDao().updateEndDate(gc.groupId, newEnd)
                if (uid != null) {
                    firestore.collection("groupChallenges").document(gc.groupId)
                        .set(mapOf("endDate" to newEnd), SetOptions.merge())
                }
            }
            Timber.d("DEBUG: all challenge endDates set to now+5s")
        }
    }

    fun debugResetBudgetToday() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Timber.d("DEBUG ResetBudget: start key=${DateUtils.todayKey()}")
                val todayKey = DateUtils.todayKey()
                val uid = firebaseAuth.currentUser?.uid ?: return@launch
                val prefs = context.getSharedPreferences("detox_settings", Context.MODE_PRIVATE)
                val allActive = database.challengeDao().getActiveChallengesList()
                // limitType is stored lowercase in Room ("time_budget")
                val budgetChallenges = allActive.filter { it.limitType == "time_budget" }
                Timber.d("DEBUG ResetBudget: found ${budgetChallenges.size} TIME_BUDGET challenges (total active=${allActive.size})")
                budgetChallenges.forEach { challenge ->
                    val totalBudgetMs = (challenge.dailyBudgetMinutes ?: 0).toLong() * 60_000L
                    val key = "${challenge.id}_${todayKey}"
                    Timber.d("DEBUG ResetBudget: challengeId=${challenge.id} key=$key totalBudgetMs=$totalBudgetMs")
                    val existing = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG ResetBudget: existing row=${existing != null} existingUsedMs=${existing?.budgetUsedMs}")
                    val log = existing?.copy(budgetUsedMs = 0L, budgetRemainingMs = totalBudgetMs)
                        ?: DailyLogEntity(
                            id = key, challengeId = challenge.id, date = todayKey,
                            budgetUsedMs = 0L, budgetRemainingMs = totalBudgetMs,
                            totalMinutes = 0, openCount = 0, pointsEarned = 0,
                            limitExceeded = false, moneyLostCents = 0
                        )
                    Timber.d("DEBUG ResetBudget: before upsert key=$key usedMs=0 remainingMs=$totalBudgetMs")
                    database.dailyLogDao().upsert(log)
                    val verify = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG ResetBudget: after upsert verify usedMs=${verify?.budgetUsedMs} remainingMs=${verify?.budgetRemainingMs}")
                    firestore.collection("users").document(uid)
                        .collection("dailyLogs").document(key)
                        .set(mapOf("budgetUsedMs" to 0L, "budgetRemainingMs" to totalBudgetMs, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
                    prefs.edit()
                        .remove("budget_committed_ms_${challenge.id}")
                        .remove("budget_session_end_time")
                        .apply()
                }
                Timber.d("DEBUG: budget reset for all TIME_BUDGET challenges")
            } catch (e: Exception) {
                Timber.e(e, "DEBUG ResetBudget: EXCEPTION")
            }
        }
    }

    fun debugExhaustBudgetNow() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Timber.d("DEBUG ExhaustBudget: start key=${DateUtils.todayKey()}")
                val todayKey = DateUtils.todayKey()
                val uid = firebaseAuth.currentUser?.uid ?: return@launch
                val allActive = database.challengeDao().getActiveChallengesList()
                // limitType is stored lowercase in Room ("time_budget")
                val budgetChallenges = allActive.filter { it.limitType == "time_budget" }
                Timber.d("DEBUG ExhaustBudget: found ${budgetChallenges.size} TIME_BUDGET challenges (total active=${allActive.size})")
                budgetChallenges.forEach { challenge ->
                    val totalBudgetMs = (challenge.dailyBudgetMinutes ?: 0).toLong() * 60_000L
                    val key = "${challenge.id}_${todayKey}"
                    Timber.d("DEBUG ExhaustBudget: challengeId=${challenge.id} key=$key totalBudgetMs=$totalBudgetMs")
                    val existing = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG ExhaustBudget: existing row=${existing != null} existingUsedMs=${existing?.budgetUsedMs}")
                    val log = existing?.copy(budgetUsedMs = totalBudgetMs, budgetRemainingMs = 0L)
                        ?: DailyLogEntity(
                            id = key, challengeId = challenge.id, date = todayKey,
                            budgetUsedMs = totalBudgetMs, budgetRemainingMs = 0L,
                            totalMinutes = 0, openCount = 0, pointsEarned = 0,
                            limitExceeded = false, moneyLostCents = 0
                        )
                    Timber.d("DEBUG ExhaustBudget: before upsert key=$key usedMs=$totalBudgetMs remainingMs=0")
                    database.dailyLogDao().upsert(log)
                    val verify = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG ExhaustBudget: after upsert verify usedMs=${verify?.budgetUsedMs} remainingMs=${verify?.budgetRemainingMs}")
                    firestore.collection("users").document(uid)
                        .collection("dailyLogs").document(key)
                        .set(mapOf("budgetUsedMs" to totalBudgetMs, "budgetRemainingMs" to 0L, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
                }
                Timber.d("DEBUG: budget exhausted for all TIME_BUDGET challenges")
            } catch (e: Exception) {
                Timber.e(e, "DEBUG ExhaustBudget: EXCEPTION")
            }
        }
    }

    fun debugResetOpensToday() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Timber.d("DEBUG ResetOpens: start key=${DateUtils.todayKey()}")
                val todayKey = DateUtils.todayKey()
                val uid = firebaseAuth.currentUser?.uid ?: return@launch
                val allActive = database.challengeDao().getActiveChallengesList()
                // limitType is stored lowercase in Room ("sessions")
                val sessionChallenges = allActive.filter { it.limitType == "sessions" }
                Timber.d("DEBUG ResetOpens: found ${sessionChallenges.size} SESSIONS challenges (total active=${allActive.size})")
                sessionChallenges.forEach { challenge ->
                    val key = "${challenge.id}_${todayKey}"
                    Timber.d("DEBUG ResetOpens: challengeId=${challenge.id} key=$key")
                    val existing = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG ResetOpens: existing row=${existing != null} existingOpens=${existing?.consciousOpens}")
                    val log = existing?.copy(consciousOpens = 0)
                        ?: DailyLogEntity(
                            id = key, challengeId = challenge.id, date = todayKey,
                            consciousOpens = 0, totalMinutes = 0, openCount = 0,
                            pointsEarned = 0, limitExceeded = false, moneyLostCents = 0
                        )
                    Timber.d("DEBUG ResetOpens: before upsert key=$key opens=0")
                    database.dailyLogDao().upsert(log)
                    val verify = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG ResetOpens: after upsert verify opens=${verify?.consciousOpens}")
                    firestore.collection("users").document(uid)
                        .collection("dailyLogs").document(key)
                        .set(mapOf("consciousOpens" to 0, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
                }
                Timber.d("DEBUG: opens reset for all SESSIONS challenges")
            } catch (e: Exception) {
                Timber.e(e, "DEBUG ResetOpens: EXCEPTION")
            }
        }
    }

    fun debugMaxOpensNow() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Timber.d("DEBUG MaxOpens: start key=${DateUtils.todayKey()}")
                val todayKey = DateUtils.todayKey()
                val uid = firebaseAuth.currentUser?.uid ?: return@launch
                val allActive = database.challengeDao().getActiveChallengesList()
                // limitType is stored lowercase in Room ("sessions")
                val sessionChallenges = allActive.filter { it.limitType == "sessions" }
                Timber.d("DEBUG MaxOpens: found ${sessionChallenges.size} SESSIONS challenges (total active=${allActive.size})")
                sessionChallenges.forEach { challenge ->
                    val limit = challenge.limitValueSessions ?: 0
                    val key = "${challenge.id}_${todayKey}"
                    Timber.d("DEBUG MaxOpens: challengeId=${challenge.id} key=$key limit=$limit")
                    val existing = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG MaxOpens: existing row=${existing != null} existingOpens=${existing?.consciousOpens}")
                    val log = existing?.copy(consciousOpens = limit)
                        ?: DailyLogEntity(
                            id = key, challengeId = challenge.id, date = todayKey,
                            consciousOpens = limit, totalMinutes = 0, openCount = 0,
                            pointsEarned = 0, limitExceeded = false, moneyLostCents = 0
                        )
                    Timber.d("DEBUG MaxOpens: before upsert key=$key opens=$limit")
                    database.dailyLogDao().upsert(log)
                    val verify = database.dailyLogDao().getLogForDate(challenge.id, todayKey)
                    Timber.d("DEBUG MaxOpens: after upsert verify opens=${verify?.consciousOpens}")
                    firestore.collection("users").document(uid)
                        .collection("dailyLogs").document(key)
                        .set(mapOf("consciousOpens" to limit, "updatedAt" to System.currentTimeMillis()), SetOptions.merge())
                }
                Timber.d("DEBUG: opens maxed for all SESSIONS challenges")
            } catch (e: Exception) {
                Timber.e(e, "DEBUG MaxOpens: EXCEPTION")
            }
        }
    }

    fun debugCompleteGroupChallenge(groupId: String, onResult: (String?) -> Unit) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            cloudFunctionsService.completeGroupChallenge(groupId)
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message) }
        }
    }

    fun debugFailMeInGroupChallenge(groupId: String, onResult: (String?) -> Unit) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            val userId = firebaseAuth.currentUser?.uid ?: run { onResult("Not logged in"); return@launch }
            cloudFunctionsService.failGroupParticipant(groupId, userId)
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message) }
        }
    }

    fun debugSetGroupChallengeEndNow(groupId: String, onResult: (String?) -> Unit) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val newEnd = System.currentTimeMillis() + 5_000L
                database.groupChallengeDao().updateEndDate(groupId, newEnd)
                firestore.collection("groupChallenges").document(groupId)
                    .set(mapOf("endDate" to newEnd), SetOptions.merge())
                    .await()
            }.onSuccess { onResult(null) }
             .onFailure { onResult(it.message) }
        }
    }

    fun debugLoadDailyLogsToday() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            _debugDailyLogs.value = database.dailyLogDao().getAllForDate(DateUtils.todayKey())
        }
    }

    fun debugClearDailyLogsToday() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            database.dailyLogDao().deleteAllForDate(DateUtils.todayKey())
            _debugDailyLogs.value = emptyList()
            Timber.d("DEBUG: all daily logs for today cleared")
        }
    }

    fun debugLoadActiveChallenges() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(Dispatchers.IO) {
            _debugActiveChallenges.value = database.challengeDao().getActiveChallengesList()
        }
    }

    companion object {
        const val TAG_MANUAL_EVALUATION = "manual_evaluation"
    }
}
