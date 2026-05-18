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
     * Loads domains from [UPDATED_FILE_NAME] in filesDir if present, otherwise from assets.
     * Thread-safe: the new set is atomically swapped in after parsing is complete.
     */
    fun loadDomains(context: Context) {
        val updatedFile = File(context.filesDir, UPDATED_FILE_NAME)
        val (newDomains, source) = if (updatedFile.exists() && updatedFile.length() > 0) {
            val dateStr = SimpleDateFormat("dd. MMM yyyy", Locale.GERMAN)
                .format(Date(updatedFile.lastModified()))
            parseLines(updatedFile.bufferedReader().lineSequence()) to "updated ($dateStr)"
        } else {
            parseLines(
                context.assets.open("adult_domains.txt").bufferedReader().lineSequence()
            ) to "bundled"
        }
        domains = newDomains
        domainsCount = newDomains.size
        domainSource = source
        Timber.d("Adult domains loaded: ${newDomains.size} (source: $source)")
    }

    /**
     * Returns true when [url] points to a blocked adult domain.
     * Checks exact host match and parent-domain stripping for O(1) performance:
     *   "www.example.com" → checks "www.example.com", then "example.com"
     */
    fun isBlocked(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        if (domains.contains(host)) return true
        var remaining = host
        while (remaining.contains('.')) {
            remaining = remaining.substringAfter('.')
            if (domains.contains(remaining)) return true
        }
        return false
    }

    /**
     * Checks whether a bare [domain] string (no scheme) is in the blocklist.
     * Used by the debug panel "Test domain" feature.
     */
    fun isDomainBlocked(domain: String): Boolean {
        val h = domain.lowercase().trimStart('.')
        if (domains.contains(h)) return true
        var remaining = h
        while (remaining.contains('.')) {
            remaining = remaining.substringAfter('.')
            if (domains.contains(remaining)) return true
        }
        return false
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
