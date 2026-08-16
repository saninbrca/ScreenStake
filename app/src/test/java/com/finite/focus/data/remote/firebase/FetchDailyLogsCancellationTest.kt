package com.finite.focus.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Guards the evidence FEED for the settlement verdict: `FirestoreService.fetchDailyLogs`.
 *
 * This one is a step removed from the verdict and is the more dangerous of the three sites for
 * exactly that reason. `SyncRepositoryImpl` restores these rows into Room; the Soft verdict then
 * reads Room, later and in a different coroutine. So a cancelled fetch that returns `emptyList()`
 * does not just lose this pass — it leaves a permanent hole in Room that a LATER, perfectly healthy
 * settlement pass reads as "no breach ever recorded" and settles COMPLETED. The later pass has no
 * way to tell it is looking at a gap rather than a clean run.
 *
 * Both tests drive the failure through the same `try` block that wraps the whole Firestore chain,
 * so they exercise the real catch clauses without needing a GMS `Task` on the unit-test classpath.
 */
class FetchDailyLogsCancellationTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var service: FirestoreService

    @Before
    fun setUp() {
        firestore = mockk()
        service = FirestoreService(firestore, mockk(relaxed = true))
    }

    @Test
    fun `a cancelled fetch propagates instead of reporting an empty history`() = runTest {
        val thrown = CancellationException("sync scope torn down")
        every { firestore.collection("users") } throws thrown

        val propagated = try {
            val logs = service.fetchDailyLogs("u1", "c1")
            fail("expected the cancellation to propagate, got ${logs.size} log(s)")
            null
        } catch (e: CancellationException) {
            e
        }

        assertNotNull("a cancelled fetch must not be reported as a complete, empty history", propagated)
    }

    @Test
    fun `an ordinary fetch failure still fails open to an empty list`() = runTest {
        // Unchanged behaviour, and deliberately so: offline is the common case and must keep
        // degrading quietly rather than failing the whole sync.
        every { firestore.collection("users") } throws IOException("offline")

        assertEquals(emptyList<Any>(), service.fetchDailyLogs("u1", "c1"))
    }
}
