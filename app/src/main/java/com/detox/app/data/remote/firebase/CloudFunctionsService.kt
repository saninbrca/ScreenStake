package com.detox.app.data.remote.firebase

import com.detox.app.domain.model.PaymentIntentData
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudFunctionsService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val firebaseAuth: FirebaseAuth
) {
    companion object {
        private const val BASE_URL = "https://us-central1-detox-33208.cloudfunctions.net/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    // ── Core HTTP helper ───────────────────────────────────────────────────────

    private suspend fun callFunction(name: String, body: Map<String, Any?>): Map<String, Any?> {
        val user = firebaseAuth.currentUser
            ?: throw IllegalStateException("User not logged in")
        val token = user.getIdToken(true).await().token
            ?: throw IllegalStateException("Failed to obtain Firebase ID token")

        Timber.d("callFunction: %s uid=%s", name, user.uid)

        val requestBody = body.toJsonObject().toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL$name")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        val responseText = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errorMsg = runCatching { JSONObject(text).optString("error", text) }
                        .getOrDefault(text)
                    Timber.e("callFunction: HTTP %d from %s: %s", response.code, name, errorMsg)
                    throw Exception(errorMsg)
                }
                text
            }
        }

        return JSONObject(responseText).toMap()
    }

    // ── JSON helpers ───────────────────────────────────────────────────────────

    private fun Map<String, Any?>.toJsonObject(): JSONObject {
        val obj = JSONObject()
        forEach { (k, v) -> obj.put(k, v.toJsonValue()) }
        return obj
    }

    private fun Any?.toJsonValue(): Any = when (this) {
        null -> JSONObject.NULL
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (this as Map<String, Any?>).toJsonObject()
        is List<*> -> JSONArray().also { arr -> forEach { arr.put(it.toJsonValue()) } }
        is Boolean, is Int, is Long, is Double, is Float, is String -> this
        else -> toString()
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            map[key] = when (val v = get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> v.toMap()
                is JSONArray -> (0 until v.length()).map { v.get(it) }
                else -> v
            }
        }
        return map
    }

    // ── Stripe / Hard Mode ─────────────────────────────────────────────────────

    suspend fun createPaymentIntent(
        amountCents: Int,
        durationDays: Int,
        challengeId: String
    ): Result<PaymentIntentData> = try {
        val response = callFunction(
            "createPaymentIntent",
            mapOf("amountCents" to amountCents, "durationDays" to durationDays, "challengeId" to challengeId)
        )
        val paymentIntentId = response["paymentIntentId"] as String
        val clientSecret = response["clientSecret"] as String
        val isImmediate = response["isImmediateCapture"] as? Boolean ?: (durationDays > 7)
        Timber.d("createPaymentIntent: %s immediate=%s", paymentIntentId, isImmediate)
        Result.success(PaymentIntentData(paymentIntentId, clientSecret, isImmediate))
    } catch (e: Exception) {
        Timber.e(e, "createPaymentIntent failed")
        Result.failure(e)
    }

    suspend fun capturePayment(paymentIntentId: String): Result<Unit> = try {
        callFunction("capturePayment", mapOf("paymentIntentId" to paymentIntentId))
        Timber.d("capturePayment: %s", paymentIntentId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "capturePayment failed — %s", paymentIntentId)
        Result.failure(e)
    }

    suspend fun cancelOrRefundPayment(
        paymentIntentId: String,
        wasImmediate: Boolean
    ): Result<Unit> = try {
        callFunction(
            "cancelOrRefundPayment",
            mapOf("paymentIntentId" to paymentIntentId, "wasImmediate" to wasImmediate)
        )
        Timber.d("cancelOrRefundPayment: %s wasImmediate=%s", paymentIntentId, wasImmediate)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "cancelOrRefundPayment failed — %s", paymentIntentId)
        Result.failure(e)
    }

    // ── Group Challenge ────────────────────────────────────────────────────────

    suspend fun createGroupChallenge(
        groupId: String,
        code: String,
        groupData: Map<String, Any?>
    ): Result<GroupChallengeCreationData> = try {
        val response = callFunction(
            "createGroupChallenge",
            mapOf("groupId" to groupId, "code" to code, "groupData" to groupData)
        )
        val returnedCode = response["code"] as String
        val paymentIntentId = response["paymentIntentId"] as String
        val clientSecret = response["clientSecret"] as String
        Timber.d("createGroupChallenge: groupId=%s code=%s", groupId, returnedCode)
        Result.success(GroupChallengeCreationData(returnedCode, paymentIntentId, clientSecret))
    } catch (e: Exception) {
        Timber.e(e, "createGroupChallenge failed — groupId=%s", groupId)
        Result.failure(e)
    }

    data class GroupChallengeCreationData(
        val code: String,
        val paymentIntentId: String,
        val clientSecret: String
    )

    suspend fun joinGroupChallenge(
        groupId: String,
        userId: String,
        displayName: String
    ): Result<PaymentIntentData> = try {
        val response = callFunction(
            "joinGroupChallenge",
            mapOf("groupId" to groupId, "userId" to userId, "displayName" to displayName)
        )
        val paymentIntentId = response["paymentIntentId"] as String
        val clientSecret = response["clientSecret"] as String
        val isImmediate = response["isImmediateCapture"] as? Boolean ?: true
        Timber.d("joinGroupChallenge: groupId=%s userId=%s", groupId, userId)
        Result.success(PaymentIntentData(paymentIntentId, clientSecret, isImmediate))
    } catch (e: Exception) {
        Timber.e(e, "joinGroupChallenge failed — groupId=%s", groupId)
        Result.failure(e)
    }

    suspend fun startGroupChallenge(groupId: String): Result<Unit> = try {
        callFunction("startGroupChallenge", mapOf("groupId" to groupId))
        Timber.d("startGroupChallenge: groupId=%s", groupId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "startGroupChallenge failed — groupId=%s", groupId)
        Result.failure(e)
    }

    suspend fun failGroupParticipant(groupId: String, userId: String): Result<Unit> = try {
        callFunction("failParticipant", mapOf("groupId" to groupId, "userId" to userId))
        Timber.d("failGroupParticipant: groupId=%s userId=%s", groupId, userId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "failGroupParticipant failed — groupId=%s userId=%s", groupId, userId)
        Result.failure(e)
    }

    suspend fun completeGroupChallenge(groupId: String): Result<Unit> = try {
        callFunction("completeGroupChallenge", mapOf("groupId" to groupId))
        Timber.d("completeGroupChallenge: groupId=%s", groupId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "completeGroupChallenge failed — groupId=%s", groupId)
        Result.failure(e)
    }

    suspend fun cancelGroupChallenge(groupId: String): Result<Unit> = try {
        callFunction("cancelGroupChallenge", mapOf("groupId" to groupId))
        Timber.d("cancelGroupChallenge: groupId=%s", groupId)
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "cancelGroupChallenge failed — groupId=%s", groupId)
        Result.failure(e)
    }
}
