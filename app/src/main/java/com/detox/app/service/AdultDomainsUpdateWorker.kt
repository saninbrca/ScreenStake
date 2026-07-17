package com.detox.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.detox.app.domain.model.AdultDomains
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Monthly WorkManager worker that downloads a fresh adult-domain blocklist from OISD
 * (nsfw_small — the NSFW list, ~20k entries; NOT small.oisd.nl, which is the general
 * ad-blocking list and contains no adult domains) and saves it to
 * [Context.getFilesDir]/adult_domains_updated.txt. [AdultDomains.loadDomains] MERGES
 * it into the bundled list, so updates only ever add coverage.
 *
 * After saving, [AdultDomains.loadDomains] is called so the running
 * [AppDetectionAccessibilityService] immediately uses the new list without a restart.
 *
 * Scheduled in [com.detox.app.DetoxApplication] with [ExistingPeriodicWorkPolicy.KEEP]
 * so it survives process restarts without resetting the 30-day interval.
 *
 * Huawei-safe: WorkManager only — never AlarmManager, never FCM.
 */
@HiltWorker
class AdultDomainsUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "adult_domains_update"
        private const val OISD_URL = "https://nsfw-small.oisd.nl/"

        /** Must be present in any genuine NSFW list — guards against a wrong endpoint. */
        private const val CANARY_DOMAIN = "pornhub.com"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Timber.d("AdultDomainsUpdateWorker: starting download from OISD")
        try {
            val raw = download(OISD_URL)
            if (raw.isNullOrBlank()) {
                Timber.w("AdultDomainsUpdateWorker: download returned empty — keeping current list")
                return@withContext Result.retry()
            }

            val domains = parseAdblock(raw)
            if (domains.size < 10_000) {
                Timber.w("AdultDomainsUpdateWorker: parsed only ${domains.size} domains — unexpectedly small, keeping current list")
                return@withContext Result.retry()
            }

            // Canary: the download must actually be an NSFW list. The pre-2026-07-17
            // bug pointed this worker at small.oisd.nl (ad-block list, >10k entries,
            // zero adult domains) and silently disabled adult blocking on updated
            // devices. Never save a list that fails the canary.
            if (CANARY_DOMAIN !in domains) {
                Timber.w("AdultDomainsUpdateWorker: canary '$CANARY_DOMAIN' missing — not an NSFW list, keeping current list")
                return@withContext Result.retry()
            }

            val outFile = File(context.filesDir, AdultDomains.UPDATED_FILE_NAME)
            outFile.bufferedWriter().use { writer ->
                writer.write("# oisd nsfw_small — auto-updated by AdultDomainsUpdateWorker\n")
                for (domain in domains.sorted()) {
                    writer.write(domain)
                    writer.newLine()
                }
            }
            Timber.d("AdultDomainsUpdateWorker: saved ${domains.size} domains to ${outFile.absolutePath}")

            // Reload in-process so the running AccessibilityService picks it up immediately
            AdultDomains.loadDomains(context)

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AdultDomainsUpdateWorker: update failed")
            Result.retry()
        }
    }

    private fun download(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DetoxApp-AdultDomainsUpdater/1.0")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("AdultDomainsUpdateWorker: HTTP ${response.code} from $url")
                null
            } else {
                response.body?.string()
            }
        }
    }

    /** Parses AdBlock Plus filter syntax: ||domain.com^ per line. */
    private fun parseAdblock(text: String): Set<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("||") && it.endsWith("^") }
            .map { it.removePrefix("||").removeSuffix("^").lowercase() }
            .filter { it.contains('.') && !it.contains(' ') }
            .toHashSet()
    }
}
