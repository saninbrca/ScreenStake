package com.detox.app.presentation.screens.settings

import com.detox.app.data.local.db.entity.GroupChallengeEntity
import com.detox.app.domain.model.Challenge
import com.detox.app.domain.model.ChallengeMode
import com.detox.app.domain.model.ChallengeStatus
import com.detox.app.domain.model.DailyLog
import com.detox.app.domain.model.LimitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the GDPR export serialiser.
 *
 * The two defects this file exists to prevent regressing:
 *  1. the export gathered `getActiveChallengesList()`, so completed/failed/ended challenges — the
 *     bulk of a long-time user's history — were missing, and
 *  2. daily logs were never exported at all, despite the KDoc claiming they were.
 *
 * Plus the escaping the old string concatenation lacked: `appDisplayName` and `customMotivation`
 * are free text, so one `"` produced a file no JSON parser would open.
 */
class DataExportJsonTest {

    private fun challenge(
        id: String,
        status: ChallengeStatus,
        appDisplayName: String = "TikTok",
        customMotivation: String? = null,
    ) = Challenge(
        id = id,
        appPackageName = "com.tiktok",
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = appDisplayName,
        mode = ChallengeMode.SOFT,
        limitType = LimitType.TIME,
        limitValueMinutes = 60,
        limitValueSessions = null,
        startDate = 1_000L,
        endDate = 2_000L,
        amountCents = null,
        stripePaymentIntentId = null,
        customMotivation = customMotivation,
        status = status,
        createdAt = 500L,
    )

    private fun dailyLog(id: String, challengeId: String) = DailyLog(
        id = id,
        challengeId = challengeId,
        date = 1_500L,
        totalMinutes = 42,
        openCount = 7,
        pointsEarned = 3,
        limitExceeded = false,
        moneyLostCents = 0,
    )

    private fun groupChallenge(
        groupId: String = "g1",
        creatorUserId: String = "someone-else",
        participantsJson: String = """[{"userId":"other-user","username":"Alex","opens":12}]""",
    ) = GroupChallengeEntity(
        groupId = groupId,
        code = "ABC123",
        creatorUserId = creatorUserId,
        appPackageNames = "com.tiktok,com.instagram.android",
        appDisplayName = "TikTok",
        limitType = "TIME",
        limitValueMinutes = 60,
        limitValueSessions = null,
        durationDays = 7,
        buyInCents = 500,
        maxParticipants = 5,
        startDate = 1_000L,
        endDate = 2_000L,
        bonusEnabled = 0,
        status = "active",
        participantsJson = participantsJson,
    )

    private fun build(
        challenges: List<Challenge> = emptyList(),
        dailyLogs: List<DailyLog> = emptyList(),
        groupChallenges: List<GroupChallengeEntity> = emptyList(),
        account: Map<String, Any?> = mapOf("firebaseUid" to "uid-1"),
        permissionStatus: Map<String, Any?>? = null,
        notIncluded: List<Map<String, Any?>> = emptyList(),
        selfUserId: String? = "uid-1",
    ) = DataExportJson.build(
        generatedAt = 1_700_000_000_000L,
        appVersion = "1.2.3",
        account = account,
        challenges = challenges,
        dailyLogs = dailyLogs,
        groupChallenges = groupChallenges,
        permissionStatus = permissionStatus,
        notIncluded = notIncluded,
        selfUserId = selfUserId,
    )

    // ── The two confirmed gaps ─────────────────────────────────────────────────

    @Test
    fun `exports challenges of EVERY status, not just active`() {
        val json = build(
            challenges = ChallengeStatus.entries.mapIndexed { i, s -> challenge("c$i", s) }
        )

        // Every status the enum defines must survive into the export — a future status added to
        // the enum and forgotten here fails this test rather than silently vanishing from exports.
        ChallengeStatus.entries.forEach { status ->
            assertTrue(
                "status ${status.name} missing from export",
                json.contains("\"status\": \"${status.name}\"")
            )
        }
        ChallengeStatus.entries.indices.forEach { i ->
            assertTrue("challenge c$i missing", json.contains("\"id\": \"c$i\""))
        }
    }

    @Test
    fun `exports daily logs, which were previously omitted entirely`() {
        val json = build(
            challenges = listOf(challenge("c1", ChallengeStatus.COMPLETED)),
            dailyLogs = listOf(dailyLog("log1", "c1"), dailyLog("log2", "c1")),
        )

        assertTrue(json.contains("\"dailyLogs\""))
        assertTrue(json.contains("\"id\": \"log1\""))
        assertTrue(json.contains("\"id\": \"log2\""))
        // Each log carries its own challengeId, so the flat list stays attributable.
        assertTrue(json.contains("\"challengeId\": \"c1\""))
        assertTrue(json.contains("\"totalMinutes\": 42"))
        assertTrue(json.contains("\"limitExceeded\": false"))
    }

    @Test
    fun `an empty account still produces a well-formed document with every section`() {
        val json = build()

        listOf(
            "export", "account", "challenges", "dailyLogs",
            "groupChallenges", "permissionStatus", "notIncluded"
        ).forEach { assertTrue("section $it missing", json.contains("\"$it\"")) }
        assertTrue(json.contains("\"challenges\": []"))
        assertTrue(json.contains("\"permissionStatus\": null"))
        assertStructurallyValid(json)
    }

    // ── Escaping ───────────────────────────────────────────────────────────────

    @Test
    fun `free text with quotes, backslashes and newlines stays parseable`() {
        val json = build(
            challenges = listOf(
                challenge(
                    id = "c1",
                    status = ChallengeStatus.FAILED,
                    appDisplayName = """He said "hi" \ bye""",
                    customMotivation = "line one\nline two\ttabbed",
                )
            )
        )

        assertStructurallyValid(json)
        assertTrue(json.contains("""\"hi\""""))
        assertTrue(json.contains("""\\"""))
        assertTrue(json.contains("""line one\nline two\ttabbed"""))
        // The raw newline must not survive into the string literal.
        assertFalse(json.contains("line one\nline two"))
    }

    @Test
    fun `control characters are escaped as unicode`() {
        val json = build(
            challenges = listOf(
                challenge("c1", ChallengeStatus.ACTIVE, appDisplayName = "bell\u0007end")
            )
        )

        // Raw string: no escape processing, so this is the literal 13 characters the writer emits.
        assertTrue(json.contains("""bell\u0007end"""))
        assertStructurallyValid(json)
    }

    // ── Scope: never another user's data ───────────────────────────────────────

    @Test
    fun `group export drops co-participant data and the creator uid`() {
        val json = build(groupChallenges = listOf(groupChallenge(creatorUserId = "someone-else")))

        assertFalse("participant payload leaked", json.contains("participantsJson"))
        assertFalse("another user's username leaked", json.contains("Alex"))
        assertFalse("another user's uid leaked", json.contains("other-user"))
        assertFalse("creator uid leaked", json.contains("someone-else"))
        // The group's own configuration is still there — it is the exporting user's context.
        assertTrue(json.contains("\"groupId\": \"g1\""))
        assertTrue(json.contains("\"createdByYou\": false"))
    }

    @Test
    fun `createdByYou is true for a group the exporting user created`() {
        val json = build(
            groupChallenges = listOf(groupChallenge(creatorUserId = "uid-1")),
            selfUserId = "uid-1",
        )

        assertTrue(json.contains("\"createdByYou\": true"))
    }

    @Test
    fun `a null self uid never reads as creator`() {
        val json = build(
            groupChallenges = listOf(groupChallenge(creatorUserId = "someone-else")),
            selfUserId = null,
        )

        assertTrue(json.contains("\"createdByYou\": false"))
    }

    // ── Disclosed-but-absent categories ────────────────────────────────────────

    @Test
    fun `notIncluded categories are carried through with their reasons`() {
        val json = build(
            notIncluded = listOf(
                mapOf("category" to "Support requests", "reason" to "not listable by the owner")
            )
        )

        assertTrue(json.contains("\"category\": \"Support requests\""))
        assertTrue(json.contains("\"reason\": \"not listable by the owner\""))
    }

    @Test
    fun `nested account maps such as the consent record are serialised`() {
        val json = build(
            account = linkedMapOf(
                "firebaseUid" to "uid-1",
                "email" to "a@b.com",
                "consent" to linkedMapOf(
                    "acceptedTerms" to true,
                    "confirmedAge18" to true,
                    "consentTimestamp" to null,
                ),
            )
        )

        assertTrue(json.contains("\"consent\""))
        assertTrue(json.contains("\"acceptedTerms\": true"))
        assertTrue(json.contains("\"consentTimestamp\": null"))
        assertStructurallyValid(json)
    }

    @Test
    fun `format version is stamped so consumers can tell the shapes apart`() {
        assertTrue(build().contains("\"formatVersion\": ${DataExportJson.FORMAT_VERSION}"))
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    /**
     * Minimal structural check: braces/brackets balance, and every `"` that opens a string is
     * closed, with `\"` not counting as a terminator. Enough to catch the class of corruption the
     * unescaped concatenation produced, without pulling a JSON parser into the JVM test classpath
     * (`org.json` is stubbed there — calling it throws "not mocked").
     */
    private fun assertStructurallyValid(json: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                    // A raw control char inside a string literal is invalid JSON.
                    ch < ' ' -> throw AssertionError("unescaped control char inside string")
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            assertTrue("unbalanced close at depth $depth", depth >= 0)
        }
        assertFalse("unterminated string literal", inString)
        assertEquals("unbalanced braces", 0, depth)
    }
}
