package com.finite.focus.presentation.screens.settings

import com.finite.focus.data.local.db.entity.GroupChallengeEntity
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.DailyLog

/**
 * Serialises the in-app GDPR data export (Art. 15 right of access).
 *
 * Pure and dependency-free ON PURPOSE: the gathering (Room, Firestore, FirebaseAuth) stays in
 * [SettingsViewModel] and everything below is a total function of its inputs, so the part that has
 * to be COMPLETE is the part that can be unit-tested without a device. `org.json` is deliberately
 * not used — it is unavailable in plain JVM unit tests (no Robolectric in this project), and the
 * export has always been hand-built JSON.
 *
 * Escaping is NOT optional here: `appDisplayName`, `customMotivation` and blocked domains are free
 * text the user types. The previous implementation concatenated them raw, so a single `"` in a
 * motivation produced a corrupt file — an export nobody can open is not an export.
 *
 * SCOPE — the requesting user only. Nothing here reads another user's data; see
 * [groupChallengeToMap] for the one place that actively drops co-participant data.
 */
object DataExportJson {

    /** Bumped when the exported SHAPE changes, so a consumer can tell the versions apart. */
    const val FORMAT_VERSION = 2

    /**
     * @param account identity + consent fields (see [SettingsViewModel.buildExportJson]).
     * @param challenges EVERY challenge, whatever its status — active, completed, failed, ended.
     * @param dailyLogs EVERY daily log, flat; each carries its own `challengeId`.
     * @param permissionStatus `users/{uid}/permissionStatus/current`, or null if unreadable/absent.
     * @param notIncluded disclosed categories deliberately absent, each with the reason why.
     */
    fun build(
        generatedAt: Long,
        appVersion: String,
        account: Map<String, Any?>,
        challenges: List<Challenge>,
        dailyLogs: List<DailyLog>,
        groupChallenges: List<GroupChallengeEntity>,
        permissionStatus: Map<String, Any?>?,
        notIncluded: List<Map<String, Any?>>,
        selfUserId: String?,
    ): String {
        val root = linkedMapOf<String, Any?>(
            "export" to linkedMapOf(
                "formatVersion" to FORMAT_VERSION,
                "generatedAt" to generatedAt,
                "appVersion" to appVersion,
                "description" to "Personal data held by Finite for this account, per Art. 15 GDPR.",
            ),
            "account" to account,
            "challenges" to challenges.map(::challengeToMap),
            "dailyLogs" to dailyLogs.map(::dailyLogToMap),
            "groupChallenges" to groupChallenges.map { groupChallengeToMap(it, selfUserId) },
            "permissionStatus" to permissionStatus,
            "notIncluded" to notIncluded,
        )
        return writeValue(root, indent = 0)
    }

    // ── Mappers ────────────────────────────────────────────────────────────────

    private fun challengeToMap(c: Challenge): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "status" to c.status.name,
        "mode" to c.mode.name,
        "appDisplayName" to c.appDisplayName,
        "appPackageName" to c.appPackageName,
        "appPackageNames" to c.appPackageNames,
        "blockingType" to c.blockingType.name,
        "blockedDomains" to c.blockedDomains,
        "partialBlockDomains" to c.partialBlockDomains,
        "partialBlockSections" to c.partialBlockSections.map { it.name },
        "isPartialBlockOnly" to c.isPartialBlockOnly,
        "blockAdultContent" to c.blockAdultContent,
        "limitType" to c.limitType.name,
        "limitValueMinutes" to c.limitValueMinutes,
        "limitValueSessions" to c.limitValueSessions,
        "sessionDurationMinutes" to c.sessionDurationMinutes,
        "dailyBudgetMinutes" to c.dailyBudgetMinutes,
        "scheduleStartTime" to c.scheduleStartTime,
        "scheduleEndTime" to c.scheduleEndTime,
        "activeDays" to c.activeDays,
        "startDate" to c.startDate,
        "endDate" to c.endDate,
        "createdAt" to c.createdAt,
        "customMotivation" to c.customMotivation,
        "failReason" to c.failReason,
        "completionShown" to c.completionShown,
        "groupChallengeId" to c.groupChallengeId,
        "amountCents" to c.amountCents,
        "stripePaymentIntentId" to c.stripePaymentIntentId,
        "pendingLimitValue" to c.pendingLimitValue,
        "pendingLimitAppliesAt" to c.pendingLimitAppliesAt,
        "isRedemption" to c.isRedemption,
        "redemptionEligible" to c.redemptionEligible,
        "redemptionDeadline" to c.redemptionDeadline,
        "redemptionChallengeId" to c.redemptionChallengeId,
        "originalChallengeId" to c.originalChallengeId,
    )

    private fun dailyLogToMap(l: DailyLog): Map<String, Any?> = linkedMapOf(
        "id" to l.id,
        "challengeId" to l.challengeId,
        "date" to l.date,
        "totalMinutes" to l.totalMinutes,
        "openCount" to l.openCount,
        "consciousOpens" to l.consciousOpens,
        "overlayPausedMs" to l.overlayPausedMs,
        "budgetUsedMinutes" to l.budgetUsedMinutes,
        "budgetRemainingMinutes" to l.budgetRemainingMinutes,
        "budgetUsedMs" to l.budgetUsedMs,
        "budgetRemainingMs" to l.budgetRemainingMs,
        "pointsEarned" to l.pointsEarned,
        "limitExceeded" to l.limitExceeded,
        "moneyLostCents" to l.moneyLostCents,
    )

    /**
     * The group's CONFIGURATION only.
     *
     * `participantsJson` is deliberately dropped: it holds the other participants' user ids,
     * usernames and progress, which are THEIR personal data, not the exporting user's. The
     * exporting user's own participation is fully represented — the local shadow row appears in
     * `challenges` and its usage in `dailyLogs`.
     *
     * `creatorUserId` is reduced to the boolean [createdByYou] for the same reason: when someone
     * else created the group, the raw value is another user's Firebase UID.
     */
    private fun groupChallengeToMap(
        g: GroupChallengeEntity,
        selfUserId: String?,
    ): Map<String, Any?> = linkedMapOf(
        "groupId" to g.groupId,
        "code" to g.code,
        "createdByYou" to (selfUserId != null && g.creatorUserId == selfUserId),
        "status" to g.status,
        "appDisplayName" to g.appDisplayName,
        "appPackageNames" to g.appPackageNames.split(",").filter { it.isNotBlank() },
        "blockedDomains" to (g.blockedDomains?.split(",")?.filter { it.isNotBlank() } ?: emptyList<String>()),
        "blockAdultContent" to (g.blockAdultContent != 0),
        "limitType" to g.limitType,
        "limitValueMinutes" to g.limitValueMinutes,
        "limitValueSessions" to g.limitValueSessions,
        "sessionDurationMinutes" to g.sessionDurationMinutes,
        "durationDays" to g.durationDays,
        "buyInCents" to g.buyInCents,
        "maxParticipants" to g.maxParticipants,
        "startDate" to g.startDate,
        "endDate" to g.endDate,
        "participants" to "excluded — other participants' personal data, see notIncluded",
    )

    // ── Writer ─────────────────────────────────────────────────────────────────

    private fun writeValue(value: Any?, indent: Int): String = when (value) {
        null -> "null"
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Float, is Double -> if (value.toDouble().isFinite()) value.toString() else "null"
        is String -> quote(value)
        is Map<*, *> -> writeObject(value, indent)
        is Iterable<*> -> writeArray(value, indent)
        // Enums and anything else: their string form. Never silently dropped.
        else -> quote(value.toString())
    }

    private fun writeObject(map: Map<*, *>, indent: Int): String {
        if (map.isEmpty()) return "{}"
        val pad = "  ".repeat(indent + 1)
        return map.entries.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n${"  ".repeat(indent)}}"
        ) { (k, v) -> "$pad${quote(k.toString())}: ${writeValue(v, indent + 1)}" }
    }

    private fun writeArray(items: Iterable<*>, indent: Int): String {
        val list = items.toList()
        if (list.isEmpty()) return "[]"
        val pad = "  ".repeat(indent + 1)
        return list.joinToString(
            separator = ",\n",
            prefix = "[\n",
            postfix = "\n${"  ".repeat(indent)}]"
        ) { "$pad${writeValue(it, indent + 1)}" }
    }

    /** RFC 8259 string escaping. Control characters below 0x20 MUST be escaped. */
    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                // Form feed and every other C0 control fall through to the escape branch below.
                else ->
                    if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else append(ch)
            }
        }
        append('"')
    }
}
