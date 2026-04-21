package com.detox.app.domain.usecase

import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.repository.GroupChallengeRepository
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/** Data returned to the ViewModel after the Cloud Function succeeds. */
data class GroupChallengeCreatedData(
    val groupId: String,
    val code: String,
    /** Stripe client secret — present to the creator via PaymentSheet. */
    val clientSecret: String,
    /** paymentIntentId stored so we can update the participant record. */
    val paymentIntentId: String,
)

class CreateGroupChallengeUseCase @Inject constructor(
    private val groupChallengeRepository: GroupChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService
) {

    /**
     * Creates a new group challenge server-side and returns the data needed to
     * present the creator's Stripe PaymentSheet.
     *
     * @param creatorUserId  Firebase UID of the challenge creator.
     * @param creatorDisplayName  Display name shown in the leaderboard.
     * @param appPackageNames  List of app packages to block.
     * @param appDisplayName  Human-readable name for the primary app.
     * @param limitType  TIME, SESSIONS, or TIME_BUDGET.
     * @param limitValueMinutes  Daily minute limit (TIME / TIME_BUDGET).
     * @param limitValueSessions  Daily session count (SESSIONS only).
     * @param sessionDurationMinutes  Max duration per session in minutes (SESSIONS only).
     * @param durationDays  Challenge length in days (1–30).
     * @param buyInCents  Fixed buy-in per participant (100–5000 cents / €1–€50).
     * @param maxParticipants  Maximum number of players (2–20).
     * @param startDateMs  Unix epoch of the intended start (must be ≥ now + 24 h).
     * @param bonusEnabled  Whether to award a 10% bonus to the best performer.
     *
     * @return [Result] wrapping [GroupChallengeCreatedData] on success.
     */
    suspend operator fun invoke(
        creatorUserId: String,
        creatorDisplayName: String,
        appPackageNames: List<String>,
        appDisplayName: String,
        limitType: LimitType,
        limitValueMinutes: Int,
        limitValueSessions: Int?,
        sessionDurationMinutes: Int = 5,
        durationDays: Int,
        buyInCents: Int,
        maxParticipants: Int,
        startDateMs: Long,
        bonusEnabled: Boolean
    ): Result<GroupChallengeCreatedData> {

        // ── Validation ──────────────────────────────────────────────────────────
        if (appPackageNames.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one app to block."))
        }
        if (durationDays !in 3..365) {
            return Result.failure(IllegalArgumentException("Duration must be between 3 and 365 days."))
        }
        if (buyInCents !in 1000..5000) {
            return Result.failure(IllegalArgumentException("Buy-in must be between €10 and €50."))
        }
        if (maxParticipants !in 2..20) {
            return Result.failure(IllegalArgumentException("Participants must be between 2 and 20."))
        }
        if (limitType != LimitType.TIME_BUDGET && limitValueMinutes <= 0) {
            return Result.failure(IllegalArgumentException("Time limit must be greater than 0."))
        }
        if (limitType == LimitType.SESSIONS && (limitValueSessions == null || limitValueSessions <= 0)) {
            return Result.failure(IllegalArgumentException("Session count must be greater than 0."))
        }

        val groupId = UUID.randomUUID().toString()
        val code = generateCode()
        val endDateMs = startDateMs + durationDays.toLong() * 24 * 60 * 60 * 1000L

        // Persist to Firestore via Cloud Function (validates code uniqueness,
        // creates Stripe PaymentIntent for creator, adds creator as first participant)
        val groupDataMap = mapOf(
            "groupId" to groupId,
            "creatorUserId" to creatorUserId,
            "creatorDisplayName" to creatorDisplayName,
            "appPackageNames" to appPackageNames.joinToString(","),
            "appDisplayName" to appDisplayName,
            "limitType" to limitType.name.lowercase(),
            "limitValueMinutes" to limitValueMinutes,
            "limitValueSessions" to limitValueSessions,
            "sessionDurationMinutes" to sessionDurationMinutes,
            "durationDays" to durationDays,
            "buyInCents" to buyInCents,
            "maxParticipants" to maxParticipants,
            "startDate" to startDateMs,
            "endDate" to endDateMs,
            "bonusEnabled" to bonusEnabled,
            "status" to "waiting"
        )

        val cfResult = cloudFunctionsService.createGroupChallenge(groupId, code, groupDataMap)
        if (cfResult.isFailure) {
            Timber.e(cfResult.exceptionOrNull(), "CreateGroupChallengeUseCase: cloud function failed")
            return Result.failure(cfResult.exceptionOrNull()!!)
        }

        val creationData = cfResult.getOrThrow()
        val finalCode = creationData.code

        // Fetch the server-authoritative version (Source.SERVER) to populate Room
        // NOTE: do NOT call saveGroupChallenge here — it would overwrite the Firestore document
        // with participants=[] and erase the creator entry added by the Cloud Function.
        Timber.d("Group created: $groupId — fetching from Firestore")
        groupChallengeRepository.fetchAndCacheById(groupId)
            .onSuccess { fetched ->
                val fetchedId = fetched?.groupId
                Timber.d(
                    "CreateGroupChallengeUseCase: local groupId=%s  firestore groupId=%s  match=%b",
                    groupId, fetchedId, groupId == fetchedId
                )
                if (fetched == null) {
                    Timber.w(
                        "CreateGroupChallengeUseCase: Firestore returned null for groupId=%s " +
                                "— document may not have committed yet", groupId
                    )
                }
            }
            .onFailure { e ->
                Timber.w(e, "CreateGroupChallengeUseCase: fetchAndCacheById failed — using local cache")
            }

        Timber.d("CreateGroupChallengeUseCase: created groupId=%s code=%s", groupId, finalCode)
        return Result.success(
            GroupChallengeCreatedData(
                groupId = groupId,
                code = finalCode,
                clientSecret = creationData.clientSecret,
                paymentIntentId = creationData.paymentIntentId
            )
        )
    }

    /** Generates a random 6-character uppercase alphanumeric code. */
    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I to avoid confusion
        return (1..6).map { chars.random() }.joinToString("")
    }
}
