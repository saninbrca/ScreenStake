package com.detox.app.domain.model

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adult-content domain blocklist loaded at service start into a [HashSet] for O(1) lookup.
 *
 * Priority:
 *   1. [Context.getFilesDir]/adult_domains_updated.txt  — downloaded by [AdultDomainsUpdateWorker]
 *   2. assets/adult_domains.txt                          — bundled at build time (~130k domains)
 *
 * Call [loadDomains] once in AppDetectionAccessibilityService.onCreate().
 * The monthly update worker calls [loadDomains] again after saving the new file,
 * so the running service always uses the freshest list without a restart.
 */
object AdultDomains {

    const val UPDATED_FILE_NAME = "adult_domains_updated.txt"

    @Volatile private var domains: HashSet<String> = HashSet()
    @Volatile var domainsCount: Int = 0
        private set
    @Volatile var domainSource: String = "not loaded"
        private set

    /**
     * Loads the bundled asset list and MERGES [UPDATED_FILE_NAME] into it if present.
     * Merge (never replace): a bad monthly download must not be able to shrink
     * coverage below the bundled baseline.
     * Thread-safe: the new set is atomically swapped in after parsing is complete.
     */
    fun loadDomains(context: Context) {
        val bundled = parseLines(
            context.assets.open("adult_domains.txt").bufferedReader().lineSequence()
        )

        val updatedFile = File(context.filesDir, UPDATED_FILE_NAME)

        // Self-heal: a worker bug (fixed 2026-07-17) downloaded OISD *Small* — the
        // general AD-BLOCKING list, not the NSFW list — into the updated file,
        // silently replacing adult coverage with ad/tracker domains. Detect the old
        // header and delete so those domains never (re)enter the set.
        if (updatedFile.exists()) {
            val header = updatedFile.bufferedReader().use { it.readLine() ?: "" }
            if (header.contains("OISD Small")) {
                Timber.w("AdultDomains: updated file is the ad-block Small list (old worker bug) — deleting")
                updatedFile.delete()
            }
        }

        val (newDomains, source) = if (updatedFile.exists() && updatedFile.length() > 0) {
            val dateStr = SimpleDateFormat("dd. MMM yyyy", Locale.GERMAN)
                .format(Date(updatedFile.lastModified()))
            val updated = parseLines(updatedFile.bufferedReader().lineSequence())
            bundled.apply { addAll(updated) } to "bundled+updated ($dateStr)"
        } else {
            bundled to "bundled"
        }
        domains = newDomains
        domainsCount = newDomains.size
        domainSource = source
        Timber.d("Adult domains loaded: ${newDomains.size} (source: $source)")
    }

    /**
     * Returns true when [url] points to a blocked adult domain.
     * The URL must carry a scheme — `Uri.parse("example.com").host` is null.
     */
    fun isBlocked(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        return hostMatches(host)
    }

    /**
     * Checks whether a bare [domain] string (no scheme) is in the blocklist.
     * Used by the debug panel "Test domain" feature and unit tests.
     */
    fun isDomainBlocked(domain: String): Boolean = hostMatches(domain)

    /**
     * Subdomain-aware host match: true when the host EQUALS a listed domain or is a
     * subdomain of one ("de.pornhub.com" matches list entry "pornhub.com").
     *
     * Checks every dot-boundary suffix of the host against the HashSet — semantically
     * identical to `host == d || host.endsWith(".$d")` for every listed domain d, but
     * O(label count) lookups instead of an O(list size) scan. Never a bare substring
     * match: "notpornhub.com" and "pornhub.com.evil.com" do NOT match "pornhub.com"
     * (their dot-boundary suffixes are "com" / "com.evil.com", "evil.com", "com").
     */
    private fun hostMatches(rawHost: String): Boolean {
        val host = rawHost.lowercase().trim('.')
        if (domains.contains(host)) return true
        var remaining = host
        while (remaining.contains('.')) {
            remaining = remaining.substringAfter('.')
            if (domains.contains(remaining)) return true
        }
        return false
    }

    /** Swaps in a fixed domain set for JVM unit tests (no Context/assets available). */
    @androidx.annotation.VisibleForTesting
    internal fun setDomainsForTest(testDomains: Set<String>) {
        domains = HashSet(testDomains)
        domainsCount = testDomains.size
        domainSource = "test"
    }

    private fun parseLines(lines: Sequence<String>): HashSet<String> {
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                // Strip hosts-file IP prefixes if present
                val stripped = when {
                    line.startsWith("0.0.0.0 ") -> line.removePrefix("0.0.0.0 ").trim()
                    line.startsWith("127.0.0.1 ") -> line.removePrefix("127.0.0.1 ").trim()
                    else -> line
                }
                stripped.lowercase()
            }
            .filter { it.contains('.') && !it.contains(' ') }
            .toHashSet()
    }
}
