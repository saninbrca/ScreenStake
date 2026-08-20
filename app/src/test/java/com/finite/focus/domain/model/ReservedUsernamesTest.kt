package com.finite.focus.domain.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservedUsernamesTest {

    @Test
    fun `system and authority handles are reserved`() {
        listOf(
            "admin", "administrator", "root", "support", "help", "staff",
            "team", "mod", "moderator", "official", "system",
            "finite", "finiteapp", "api", "null", "undefined",
            "me", "you", "everyone", "here",
        ).forEach {
            assertTrue("$it must be reserved", ReservedUsernames.isReserved(it))
        }
    }

    @Test
    fun `matching is case-insensitive`() {
        listOf("ADMIN", "Admin", "aDmIn", "SUPPORT", "Finite").forEach {
            assertTrue("$it must be reserved regardless of case", ReservedUsernames.isReserved(it))
        }
    }

    @Test
    fun `surrounding whitespace does not smuggle a reserved name through`() {
        listOf(" admin", "admin ", "  Admin  ", "\tadmin\n").forEach {
            assertTrue("${'"'}$it${'"'} must be reserved", ReservedUsernames.isReserved(it))
        }
    }

    @Test
    fun `ordinary handles are not reserved`() {
        listOf("sanin", "admins", "adminx", "myadmin", "team_rocket", "helper", "systematic")
            .forEach {
                assertFalse("$it must not be reserved", ReservedUsernames.isReserved(it))
            }
    }

    @Test
    fun `every entry is already lowercase`() {
        ReservedUsernames.ENTRIES.forEach {
            assertEquals("entry '$it' must be stored lowercase", it.lowercase(), it)
        }
    }

    /**
     * The Kotlin list is a UX fast-fail only — `firestore.rules` is the enforcement copy.
     * If the two drift, the client rejects names the server accepts (or the reverse), which
     * is exactly the hole this guard exists to close.
     */
    @Test
    fun `firestore rules carry the identical reserved list`() {
        val rules = File("../firestore.rules")
            .takeIf { it.exists() }
            ?: File("firestore.rules")
        assertTrue("firestore.rules not found from ${File(".").absolutePath}", rules.exists())

        val block = Regex("""!\(username in \[(.*?)]\)""", RegexOption.DOT_MATCHES_ALL)
            .find(rules.readText())
        assertTrue("no reserved-username list found in firestore.rules", block != null)

        val inRules = Regex("'([^']+)'").findAll(block!!.groupValues[1])
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "firestore.rules and ReservedUsernames.ENTRIES have drifted",
            ReservedUsernames.ENTRIES,
            inRules
        )
    }
}
