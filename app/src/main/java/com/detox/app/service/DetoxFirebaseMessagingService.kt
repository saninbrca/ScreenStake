package com.detox.app.service

import com.detox.app.data.remote.firebase.FirebaseAuthService
import com.detox.app.data.remote.firebase.FirestoreService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles incoming FCM messages and token refreshes.
 *
 * Token is saved to Firestore so the server can target this device for push notifications.
 * Message payload must include a `type` key:
 *   - "daily_report"     → posts a daily summary notification
 *   - "challenge_done"   → posts a challenge-completed notification
 */
@AndroidEntryPoint
class DetoxFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var firestoreService: FirestoreService

    @Inject
    lateinit var firebaseAuthService: FirebaseAuthService

    // Use a dedicated scope so token saves survive the message handler lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob())

    override fun onNewToken(token: String) {
        // Log the full token so it can be copied from Logcat and used for
        // manual FCM send via the Firebase Console or cURL during testing.
        Timber.d("FCM token refreshed: $token")
        val uid = firebaseAuthService.currentUserId() ?: run {
            Timber.w("FCM token refreshed but no signed-in user — token not saved to Firestore")
            return
        }
        serviceScope.launch {
            firestoreService.saveFcmToken(uid, token)
            Timber.d("FCM token saved to Firestore for uid=$uid")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Timber.d("FCM message received: ${message.data}")
        NotificationHelper.createChannels(applicationContext)

        when (message.data["type"]) {
            "daily_report" -> {
                val onTrack = message.data["onTrackCount"]?.toIntOrNull() ?: 0
                val total = message.data["totalCount"]?.toIntOrNull() ?: 0
                NotificationHelper.sendDailyReport(applicationContext, onTrack, total)
            }

            "challenge_completed" -> {
                val appName = message.data["appName"] ?: return
                NotificationHelper.sendChallengeCompleted(applicationContext, appName)
            }

            "challenge_failed" -> {
                val appName = message.data["appName"] ?: return
                NotificationHelper.sendChallengeFailed(applicationContext, appName)
            }

            else -> Timber.w("Unknown FCM message type: ${message.data["type"]}")
        }
    }
}
