package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.data.remote.firebase.CloudFunctionsService
import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.GroupChallengeFirestoreService
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.repository.GroupChallengeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class GroupChallengeAutoStartWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val groupChallengeRepository: GroupChallengeRepository,
    private val cloudFunctionsService: CloudFunctionsService,
    private val firestoreService: GroupChallengeFirestoreService,
    private val firebaseAuthService: FirebaseAuthService,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("GroupChallengeAutoStartWorker: ▶ starting")
        val userId = firebaseAuthService.currentUserId()
        if (userId == null) {
            Timber.d("GroupChallengeAutoStartWorker: user not signed in — skipping")
            return Result.success()
        }

        return try {
            val now = System.currentTimeMillis()
            val challenges = firestoreService.fetchUserGroupChallenges(userId)

            val due = challenges.filter {
                it.status == GroupChallengeStatus.WAITING && it.startDate > 0L && it.startDate <= now
            }
            Timber.d("GroupChallengeAutoStartWorker: %d challenge(s) due for auto-start", due.size)

            for (gc in due) {
                if (gc.participants.size >= 2) {
                    Timber.d(
                        "GroupChallengeAutoStartWorker: starting group %s (%d participants)",
                        gc.groupId, gc.participants.size
                    )
                    cloudFunctionsService.startGroupChallenge(gc.groupId)
                        .onSuccess {
                            Timber.d("GroupChallengeAutoStartWorker: ✓ started %s", gc.groupId)
                            groupChallengeRepository.syncGroupChallengeToLocalTracking(gc, userId)
                                .onSuccess {
                                    Timber.d(
                                        "Group challenge %s started → synced to Room as ChallengeEntity",
                                        gc.groupId
                                    )
                                }
                                .onFailure { e ->
                                    Timber.e(
                                        e,
                                        "GroupChallengeAutoStartWorker: syncToLocalTracking failed for %s",
                                        gc.groupId
                                    )
                                }
                        }
                        .onFailure { e ->
                            Timber.e(
                                e,
                                "GroupChallengeAutoStartWorker: startGroupChallenge failed for %s",
                                gc.groupId
                            )
                        }
                } else {
                    Timber.d(
                        "GroupChallengeAutoStartWorker: cancelling %s — only %d participant(s)",
                        gc.groupId, gc.participants.size
                    )
                    cloudFunctionsService.cancelGroupChallenge(gc.groupId)
                        .onSuccess {
                            Timber.d("GroupChallengeAutoStartWorker: ✓ cancelled %s", gc.groupId)
                            NotificationHelper.createChannels(applicationContext)
                            NotificationHelper.sendGroupChallengeCancelled(
                                applicationContext, gc.appDisplayName
                            )
                        }
                        .onFailure { e ->
                            Timber.e(
                                e,
                                "GroupChallengeAutoStartWorker: cancelGroupChallenge failed for %s",
                                gc.groupId
                            )
                        }
                }
            }

            Timber.d("GroupChallengeAutoStartWorker: ■ completed")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "GroupChallengeAutoStartWorker failed")
            Result.retry()
        }
    }
}
