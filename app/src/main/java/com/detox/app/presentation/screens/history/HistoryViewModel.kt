package com.detox.app.presentation.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.local.db.DetoxDatabase
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.presentation.screens.profile.HistoryItem
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.math.floor

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val database: DetoxDatabase,
    private val firebaseAuth: FirebaseAuth,
    private val firestoreService: FirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
) : ViewModel() {

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val _redemptionStarted = MutableSharedFlow<Unit>()
    val redemptionStarted: SharedFlow<Unit> = _redemptionStarted.asSharedFlow()

    private val _redemptionError = MutableSharedFlow<String>()
    val redemptionError: SharedFlow<String> = _redemptionError.asSharedFlow()

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

    fun startRedemption(originalChallengeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val original = database.challengeDao().getChallengeById(originalChallengeId)
                if (original == null) {
                    _redemptionError.emit("Challenge nicht gefunden")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val redemptionId = UUID.randomUUID().toString()

                val originalDays = ((original.endDate - original.startDate) / 86_400_000L).toInt()
                val redemptionDays = originalDays * 2
                val redemptionLimit = computeRedemptionLimit(original)
                val refundAmount = original.redemptionRefundAmount
                    ?: floor((original.amountCents ?: 0) * 0.70).toInt()

                val endDate = now + (redemptionDays * 86_400_000L)

                val redemptionEntity = ChallengeEntity(
                    id = redemptionId,
                    appPackageName = original.appPackageName,
                    appDisplayName = original.appDisplayName,
                    mode = original.mode,
                    limitType = original.limitType,
                    limitValueMinutes = if (original.limitType == "time") redemptionLimit else original.limitValueMinutes,
                    limitValueSessions = if (original.limitType == "sessions") redemptionLimit else original.limitValueSessions,
                    startDate = now,
                    endDate = endDate,
                    amountCents = 0,
                    stripePaymentIntentId = null,
                    customMotivation = original.customMotivation,
                    status = ChallengeStatus.ACTIVE.name.lowercase(),
                    createdAt = now,
                    dailyBudgetMinutes = if (original.limitType == "time_budget") redemptionLimit else original.dailyBudgetMinutes,
                    appPackageNames = original.appPackageNames,
                    blockedDomains = original.blockedDomains,
                    blockingType = original.blockingType,
                    blockAdultContent = original.blockAdultContent,
                    scheduleStartTime = original.scheduleStartTime,
                    scheduleEndTime = original.scheduleEndTime,
                    activeDays = original.activeDays,
                    sessionDurationMinutes = original.sessionDurationMinutes,
                    isRedemption = 1,
                    originalChallengeId = originalChallengeId,
                    originalPaymentIntentId = original.stripePaymentIntentId,
                    refundAmountCents = refundAmount,
                    partialBlockSections = original.partialBlockSections,
                    isPartialBlockOnly = original.isPartialBlockOnly,
                )

                database.challengeDao().insertChallenge(redemptionEntity)
                database.challengeDao().updateRedemptionChallengeId(originalChallengeId, redemptionId)

                val userId = firebaseAuthService.currentUserId()
                if (userId != null) {
                    firestoreService.saveChallenge(userId, redemptionEntity.toDomainForFirestore(
                        refundAmount = refundAmount,
                        originalPaymentIntentId = original.stripePaymentIntentId
                    ))
                    firestoreService.updateChallengeRedemptionChallengeId(userId, originalChallengeId, redemptionId)
                }

                Timber.d("Redemption challenge $redemptionId started for original $originalChallengeId")
                load()
                _redemptionStarted.emit(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start redemption challenge for $originalChallengeId")
                _redemptionError.emit("Fehler beim Starten der Comeback Challenge")
            }
        }
    }

    private fun computeRedemptionLimit(original: ChallengeEntity): Int {
        return when (original.limitType) {
            LimitType.SESSIONS.name.lowercase() ->
                floor((original.limitValueSessions ?: 1) / 2.0).toInt().coerceAtLeast(1)
            LimitType.TIME.name.lowercase() ->
                floor(original.limitValueMinutes / 2.0).toInt().coerceAtLeast(5)
            LimitType.TIME_BUDGET.name.lowercase() ->
                floor((original.dailyBudgetMinutes ?: 5) / 2.0).toInt().coerceAtLeast(5)
            else -> original.limitValueMinutes
        }
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

private fun ChallengeEntity.toDomainForFirestore(
    refundAmount: Int,
    originalPaymentIntentId: String?
): com.detox.app.domain.model.Challenge {
    val packageNames = appPackageNames
        ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
        ?: if (appPackageName.isNotBlank()) listOf(appPackageName) else emptyList()
    return com.detox.app.domain.model.Challenge(
        id = id,
        appPackageName = packageNames.firstOrNull(),
        appPackageNames = packageNames,
        appDisplayName = appDisplayName,
        mode = com.detox.app.domain.model.ChallengeMode.valueOf(mode.uppercase()),
        limitType = com.detox.app.domain.model.LimitType.valueOf(limitType.uppercase()),
        limitValueMinutes = limitValueMinutes,
        limitValueSessions = limitValueSessions,
        startDate = startDate,
        endDate = endDate,
        amountCents = amountCents,
        stripePaymentIntentId = stripePaymentIntentId,
        customMotivation = customMotivation,
        status = com.detox.app.domain.model.ChallengeStatus.valueOf(status.uppercase()),
        createdAt = createdAt,
        dailyBudgetMinutes = dailyBudgetMinutes,
        isRedemption = isRedemption != 0,
        originalChallengeId = originalChallengeId,
        originalPaymentIntentId = originalPaymentIntentId,
        refundAmountCents = refundAmount,
    )
}
