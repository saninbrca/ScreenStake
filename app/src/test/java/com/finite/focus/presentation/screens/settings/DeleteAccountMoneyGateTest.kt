package com.finite.focus.presentation.screens.settings

import android.content.Context
import android.content.SharedPreferences
import com.finite.focus.data.local.db.DetoxDatabase
import com.finite.focus.data.remote.firebase.FirebaseAuthService
import com.finite.focus.data.remote.firebase.FirestoreService
import com.finite.focus.domain.model.Challenge
import com.finite.focus.domain.model.ChallengeMode
import com.finite.focus.domain.model.ChallengeStatus
import com.finite.focus.domain.model.LimitType
import com.finite.focus.domain.repository.ChallengeRepository
import com.finite.focus.domain.repository.DailyLogRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * THE money assertion for the account-deletion flow: deleting an account must be impossible
 * while a Hard Mode challenge with a real Stripe stake is active.
 *
 * If this gate ever regresses, account deletion becomes an escape hatch out of a live stake —
 * the user walks away from a manual-capture PaymentIntent by deleting themselves. Nothing
 * else in the codebase asserts this.
 *
 * The gate is READ-ONLY to these tests. They exercise it; they never reshape it. That includes
 * its fail-closed behaviour: a failed challenge lookup must abort the deletion, not wave it
 * through.
 *
 * Group buy-ins are covered by the same gate via the group shadow row, which
 * `GroupChallengeRepositoryImpl` writes into the `challenges` table with mode="hard" and the
 * participant's paymentIntentId — asserted below so the coupling is not silently lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountMoneyGateTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var auth: FirebaseAuthService
    private lateinit var firestoreService: FirestoreService
    private lateinit var challengeRepository: ChallengeRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        // SettingsViewModel.init calls refreshPermissions(), which reads Android framework
        // statics. Stub them so construction works on the JVM — none of them are what this
        // suite is testing.
        mockkStatic(android.provider.Settings.Secure::class)
        every {
            android.provider.Settings.Secure.getString(any(), any())
        } returns ""
        mockkStatic(android.provider.Settings::class)
        every { android.provider.Settings.canDrawOverlays(any()) } returns true
        mockkStatic(androidx.core.app.NotificationManagerCompat::class)
        every {
            androidx.core.app.NotificationManagerCompat.from(any())
        } returns mockk(relaxed = true)

        auth = mockk(relaxed = true)
        firestoreService = mockk(relaxed = true)
        challengeRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getString(any()) } returns "msg"
        every { context.getString(any(), *anyVararg()) } returns "msg"

        // Re-auth succeeds throughout — this suite is about what happens AFTER it.
        coEvery { auth.reauthenticateWithPassword(any()) } returns Result.success(Unit)
        coEvery { auth.reauthenticateWithGoogle(any()) } returns Result.success(Unit)
        coEvery { auth.currentUserId() } returns "uid-1"
        coEvery { auth.deleteAccount() } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(): SettingsViewModel {
        val db = mockk<DetoxDatabase>(relaxed = true)
        return SettingsViewModel(
            firebaseAuthService = auth,
            firestoreService = firestoreService,
            challengeRepository = challengeRepository,
            dailyLogRepository = mockk<DailyLogRepository>(relaxed = true),
            database = db,
            firebaseAuth = mockk<FirebaseAuth>(relaxed = true),
            firestore = mockk<FirebaseFirestore>(relaxed = true),
            context = context,
        )
    }

    private fun challenge(
        mode: ChallengeMode,
        status: ChallengeStatus,
        paymentIntentId: String?,
    ) = Challenge(
        id = "c1",
        appPackageName = "com.example",
        appPackageNames = listOf("com.example"),
        appDisplayName = "Example",
        mode = mode,
        limitType = LimitType.TIME_BUDGET,
        limitValueMinutes = 30,
        limitValueSessions = null,
        startDate = 0L,
        endDate = 0L,
        amountCents = if (paymentIntentId != null) 2000 else null,
        stripePaymentIntentId = paymentIntentId,
        customMotivation = null,
        status = status,
        createdAt = 0L,
    )

    /** Neither the Firestore wipe nor the Auth deletion may have been attempted. */
    private fun assertNothingDeleted() {
        coVerify(exactly = 0) { firestoreService.deleteUserData(any()) }
        coVerify(exactly = 0) { auth.deleteAccount() }
    }

    // ── the gate holds ─────────────────────────────────────────────────────────

    @Test
    fun `active Hard Mode with a stake blocks deletion — password path`() = runTest(dispatcher) {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(challenge(ChallengeMode.HARD, ChallengeStatus.ACTIVE, "pi_live_123"))
        )
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        assertNothingDeleted()
    }

    @Test
    fun `active Hard Mode with a stake blocks deletion — Google path`() = runTest(dispatcher) {
        // The Google branch must not be a way around the gate: both providers converge on
        // the same post-re-auth body.
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(challenge(ChallengeMode.HARD, ChallengeStatus.ACTIVE, "pi_live_123"))
        )
        viewModel().deleteAccountWithGoogle("google-id-token")
        advanceUntilIdle()
        assertNothingDeleted()
    }

    @Test
    fun `a group buy-in blocks deletion through the shadow row`() = runTest(dispatcher) {
        // GroupChallengeRepositoryImpl writes the joiner's row as mode="hard" carrying the
        // participant's paymentIntentId, which is the only reason group stakes hit this gate.
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(challenge(ChallengeMode.HARD, ChallengeStatus.ACTIVE, "pi_group_buyin"))
        )
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        assertNothingDeleted()
    }

    @Test
    fun `the gate is FAIL-CLOSED — a failed lookup aborts deletion`() = runTest(dispatcher) {
        // Cannot verify => must not delete. Proceeding here would delete an account whose
        // stake status is unknown.
        coEvery { challengeRepository.getActiveChallengesList() } returns
            Result.failure(IllegalStateException("db unavailable"))
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        assertNothingDeleted()
    }

    @Test
    fun `one stake among several harmless challenges still blocks`() = runTest(dispatcher) {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(
                challenge(ChallengeMode.SOFT, ChallengeStatus.ACTIVE, null),
                challenge(ChallengeMode.HARD, ChallengeStatus.COMPLETED, "pi_done"),
                challenge(ChallengeMode.HARD, ChallengeStatus.ACTIVE, "pi_live_999"),
            )
        )
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        assertNothingDeleted()
    }

    // ── the gate does not over-block ───────────────────────────────────────────

    @Test
    fun `no active challenges — deletion proceeds`() = runTest(dispatcher) {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(emptyList())
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        coVerify(exactly = 1) { auth.deleteAccount() }
    }

    @Test
    fun `an active SOFT challenge has no stake and does not block`() = runTest(dispatcher) {
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(challenge(ChallengeMode.SOFT, ChallengeStatus.ACTIVE, null))
        )
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        coVerify(exactly = 1) { auth.deleteAccount() }
    }

    @Test
    fun `a legacy Hard challenge with no PaymentIntent does not block`() = runTest(dispatcher) {
        // No PI means no money to strand.
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(challenge(ChallengeMode.HARD, ChallengeStatus.ACTIVE, null))
        )
        viewModel().deleteAccount("correct-password")
        advanceUntilIdle()
        coVerify(exactly = 1) { auth.deleteAccount() }
    }

    // ── re-auth precedes the gate ──────────────────────────────────────────────

    @Test
    fun `a failed password re-auth deletes nothing and never reaches the gate`() = runTest(dispatcher) {
        coEvery { auth.reauthenticateWithPassword(any()) } returns
            Result.failure(IllegalStateException("wrong password"))
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(emptyList())
        viewModel().deleteAccount("wrong-password")
        advanceUntilIdle()
        assertNothingDeleted()
        coVerify(exactly = 0) { challengeRepository.getActiveChallengesList() }
    }

    @Test
    fun `a failed Google re-auth deletes nothing and never reaches the gate`() = runTest(dispatcher) {
        // Covers the wrong-Google-account case: the chooser returned a different account.
        coEvery { auth.reauthenticateWithGoogle(any()) } returns
            Result.failure(IllegalStateException("credential mismatch"))
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(emptyList())
        viewModel().deleteAccountWithGoogle("token-for-another-account")
        advanceUntilIdle()
        assertNothingDeleted()
        coVerify(exactly = 0) { challengeRepository.getActiveChallengesList() }
    }

    @Test
    fun `a cancelled Google re-auth deletes nothing and leaves the gate unread`() = runTest(dispatcher) {
        // 12501. Nothing is cached from the attempt — the next try re-reads the gate.
        val vm = viewModel()
        vm.onGoogleReauthCancelled()
        advanceUntilIdle()
        assertNothingDeleted()
        coVerify(exactly = 0) { challengeRepository.getActiveChallengesList() }
    }

    @Test
    fun `the gate is re-read on every attempt, never remembered`() = runTest(dispatcher) {
        // First attempt blocked by a live stake; the stake then settles; second attempt
        // must consult the repository again rather than reuse the earlier verdict.
        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(
            listOf(challenge(ChallengeMode.HARD, ChallengeStatus.ACTIVE, "pi_live_123"))
        )
        val vm = viewModel()
        vm.deleteAccount("correct-password")
        advanceUntilIdle()
        assertNothingDeleted()

        coEvery { challengeRepository.getActiveChallengesList() } returns Result.success(emptyList())
        vm.deleteAccount("correct-password")
        advanceUntilIdle()
        coVerify(exactly = 2) { challengeRepository.getActiveChallengesList() }
        coVerify(exactly = 1) { auth.deleteAccount() }
    }
}
