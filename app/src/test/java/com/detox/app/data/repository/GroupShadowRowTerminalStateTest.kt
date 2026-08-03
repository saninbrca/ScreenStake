package com.detox.app.data.repository

import com.detox.app.data.local.db.dao.ChallengeDao
import com.detox.app.data.local.db.dao.GroupChallengeDao
import com.detox.app.data.local.db.entity.ChallengeEntity
import com.detox.app.data.remote.firebase.GroupChallengeFirestoreService
import com.detox.app.domain.model.GroupChallenge
import com.detox.app.domain.model.GroupChallengeStatus
import com.detox.app.domain.model.LimitType
import com.detox.app.domain.model.Participant
import com.detox.app.domain.model.ParticipantStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Guards the two halves of "a group challenge that is over stays over, and stays in History".
 *
 * 1. **No resurrection.** `syncGroupChallengeToLocalTracking` builds its entity with
 *    `status = "active"` and runs on EVERY group sync — including while the group doc is still
 *    ACTIVE server-side because settlement has not run yet. Without the guard it flips a locally
 *    "ended" row back to enforcing, the local sweep ends it again, and the user gets a
 *    flip-flopping overlay on exactly the challenge that already finished.
 *
 * 2. **No downgrade.** `finishLocalGroupChallenge` must never rewrite an already-settled
 *    completed/failed row, but MUST be able to settle an "ended" one — that transition is how the
 *    offline enforcement stop becomes a real outcome once the device is back online.
 */
class GroupShadowRowTerminalStateTest {

    private lateinit var groupChallengeDao: GroupChallengeDao
    private lateinit var challengeDao: ChallengeDao
    private lateinit var firestoreService: GroupChallengeFirestoreService
    private lateinit var repo: GroupChallengeRepositoryImpl

    private val userId = "u_1"
    private val groupId = "g_123"
    private val localId = "group_$groupId"

    @Before
    fun setUp() {
        groupChallengeDao = mockk(relaxed = true)
        challengeDao = mockk(relaxed = true)
        firestoreService = mockk(relaxed = true)
        repo = GroupChallengeRepositoryImpl(
            groupChallengeDao, challengeDao, firestoreService, TestScope()
        )
    }

    private fun groupChallenge() = GroupChallenge(
        groupId = groupId,
        code = "ABC123",
        creatorUserId = userId,
        appPackageNames = listOf("com.tiktok"),
        appDisplayName = "TikTok",
        limitType = LimitType.TIME,
        limitValueMinutes = 60,
        limitValueSessions = null,
        durationDays = 7,
        buyInCents = 1000,
        maxParticipants = 5,
        startDate = 0L,
        endDate = 0L,
        bonusEnabled = false,
        status = GroupChallengeStatus.ACTIVE,
        participants = listOf(
            Participant(
                userId = userId,
                displayName = "Sam",
                paymentIntentId = "pi_1",
                amountCents = 1000,
                status = ParticipantStatus.ACTIVE,
            )
        ),
    )

    private fun shadowRow(status: String) = ChallengeEntity(
        id = localId,
        appPackageName = "com.tiktok",
        appDisplayName = "TikTok",
        mode = "hard",
        limitType = "time",
        limitValueMinutes = 60,
        limitValueSessions = null,
        startDate = 0L,
        endDate = 0L,
        amountCents = 1000,
        stripePaymentIntentId = "pi_1",
        customMotivation = null,
        status = status,
        createdAt = 0L,
        groupChallengeId = groupId,
    )

    @Test
    fun `sync never resurrects an ended shadow row`() = runTest {
        coEvery { challengeDao.getChallengeById(localId) } returns shadowRow("ended")
        val written = slot<ChallengeEntity>()
        coEvery { challengeDao.updateChallenge(capture(written)) } just Runs

        repo.syncGroupChallengeToLocalTracking(groupChallenge(), userId)

        assertEquals("ended", written.captured.status)
    }

    @Test
    fun `sync never resurrects a settled shadow row`() = runTest {
        coEvery { challengeDao.getChallengeById(localId) } returns shadowRow("completed")
        val written = slot<ChallengeEntity>()
        coEvery { challengeDao.updateChallenge(capture(written)) } just Runs

        repo.syncGroupChallengeToLocalTracking(groupChallenge(), userId)

        assertEquals("completed", written.captured.status)
    }

    @Test
    fun `sync still refreshes a genuinely active shadow row`() = runTest {
        coEvery { challengeDao.getChallengeById(localId) } returns shadowRow("active")
        val written = slot<ChallengeEntity>()
        coEvery { challengeDao.updateChallenge(capture(written)) } just Runs

        repo.syncGroupChallengeToLocalTracking(groupChallenge(), userId)

        assertEquals("active", written.captured.status)
    }

    @Test
    fun `settlement can still finish an ended row - offline stop becomes a real outcome`() = runTest {
        coEvery { challengeDao.getChallengeById(localId) } returns shadowRow("ended")

        repo.finishLocalGroupChallenge(groupId, succeeded = true)

        coVerify(exactly = 1) { challengeDao.updateStatus(localId, "completed") }
    }

    @Test
    fun `a settled win is never downgraded by a later snapshot`() = runTest {
        coEvery { challengeDao.getChallengeById(localId) } returns shadowRow("completed")

        repo.finishLocalGroupChallenge(groupId, succeeded = false)

        coVerify(exactly = 0) { challengeDao.updateStatus(any(), any()) }
    }

    @Test
    fun `a forfeit marks the row failed instead of deleting it - it must stay in History`() = runTest {
        val forfeited = groupChallenge().let { gc ->
            gc.copy(participants = gc.participants.map { it.copy(status = ParticipantStatus.FAILED) })
        }
        coEvery { challengeDao.getChallengeById(localId) } returns shadowRow("active")

        repo.syncGroupChallengeToLocalTracking(forfeited, userId)

        coVerify(exactly = 1) { challengeDao.updateStatus(localId, "failed") }
        coVerify(exactly = 0) { challengeDao.deleteById(any()) }
    }
}
