package com.finite.focus.presentation.screens.username

import android.content.Context
import android.content.SharedPreferences
import com.finite.focus.data.remote.firebase.FirebaseAuthService
import com.finite.focus.data.remote.firebase.FirestoreService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * QA S-02 — the picker must reject obvious system handles with a message distinct from
 * "already taken", and must keep Continue disabled while it does.
 *
 * Mirrors the manual test sequence: reserved (any case) → Reserved, a claimed handle →
 * Taken, a free handle → Available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsernameSelectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var firestore: FirestoreService
    private lateinit var auth: FirebaseAuthService
    private lateinit var context: Context

    /** The picker's own gate: Continue is enabled only on Available (see the screen). */
    private fun canSubmit(state: UsernameUiState) =
        state.availability == UsernameAvailability.Available &&
            state.usernameInput.length >= 3 && !state.isSaving

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        firestore = mockk(relaxed = true)
        auth = mockk(relaxed = true)
        context = mockk(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        coEvery { auth.currentUserId() } returns "uid-under-test"
        // No username yet — the screen stays put and runs the availability checker.
        coEvery { firestore.getUsername(any()) } returns null
        // "taken_handle" is the only claimed name in this fixture.
        coEvery { firestore.isUsernameAvailable(any()) } answers {
            firstArg<String>() != "taken_handle"
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = UsernameSelectionViewModel(firestore, auth, context)

    /** Types [input] and lets the 500 ms debounce plus the availability read settle. */
    private suspend fun kotlinx.coroutines.test.TestScope.type(
        vm: UsernameSelectionViewModel,
        input: String
    ): UsernameUiState {
        advanceUntilIdle()
        vm.onInputChange(input)
        advanceTimeBy(600)
        advanceUntilIdle()
        return vm.state.value
    }

    @Test
    fun `admin is reserved and Continue stays disabled`() = runTest(dispatcher) {
        val state = type(viewModel(), "admin")
        assertEquals(UsernameAvailability.Reserved, state.availability)
        assertFalse("Continue must stay disabled for a reserved name", canSubmit(state))
    }

    @Test
    fun `ADMIN and Admin are reserved too`() = runTest(dispatcher) {
        // The field lowercases as you type, so both arrive as "admin" — assert the
        // end state a user typing either would actually see.
        listOf("ADMIN", "Admin").forEach { typed ->
            val state = type(viewModel(), typed)
            assertEquals("'$typed' must be reserved", UsernameAvailability.Reserved, state.availability)
            assertFalse(canSubmit(state))
        }
    }

    @Test
    fun `every reserved entry is rejected as Reserved, never as Taken`() = runTest(dispatcher) {
        // Entries shorter than the 3-char minimum ("me") are unreachable in the picker —
        // the length gate rejects them first, and the rules regex ({3,20}) blocks the
        // claim server-side regardless. They stay in the list as defence in depth.
        com.finite.focus.domain.model.ReservedUsernames.ENTRIES
            .filter { it.length >= 3 }
            .forEach { name ->
                val state = type(viewModel(), name)
                assertEquals("'$name' must read as reserved", UsernameAvailability.Reserved, state.availability)
            }
    }

    @Test
    fun `a reserved entry below the length minimum is still never Available`() = runTest(dispatcher) {
        com.finite.focus.domain.model.ReservedUsernames.ENTRIES
            .filter { it.length < 3 }
            .forEach { name ->
                val state = type(viewModel(), name)
                assertEquals("'$name' must be rejected on length", UsernameAvailability.TooShort, state.availability)
                assertFalse(canSubmit(state))
            }
    }

    @Test
    fun `a claimed handle reads as Taken, not Reserved`() = runTest(dispatcher) {
        val state = type(viewModel(), "taken_handle")
        assertEquals(UsernameAvailability.Taken, state.availability)
        assertFalse(canSubmit(state))
    }

    @Test
    fun `a free normal handle is Available and enables Continue`() = runTest(dispatcher) {
        val state = type(viewModel(), "sanin_b")
        assertEquals(UsernameAvailability.Available, state.availability)
        assertTrue("Continue must enable for a free handle", canSubmit(state))
    }

    @Test
    fun `a reserved name never reaches the availability read`() = runTest(dispatcher) {
        val vm = viewModel()
        type(vm, "support")
        io.mockk.coVerify(exactly = 0) { firestore.isUsernameAvailable("support") }
    }

    @Test
    fun `save() refuses a reserved name even if the state says Available`() = runTest(dispatcher) {
        val vm = viewModel()
        // Reach Available on a free handle, then swap the input underneath without
        // letting the debounced checker run — the stale Available must not save.
        type(vm, "sanin_b")
        assertEquals(UsernameAvailability.Available, vm.state.value.availability)
        vm.onInputChange("admin")
        vm.save()
        advanceUntilIdle()
        assertEquals(UsernameAvailability.Reserved, vm.state.value.availability)
        io.mockk.coVerify(exactly = 0) { firestore.saveUsername(any(), "admin") }
    }
}
