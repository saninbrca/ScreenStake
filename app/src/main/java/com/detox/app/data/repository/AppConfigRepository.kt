package com.detox.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote app configuration mirrored from Firestore `config/app`.
 *
 * Defaults are **fail-open** for features (Hard Mode / Group Challenge enabled) and
 * **fail-safe** for blocking (no maintenance, minVersionCode = 1). A missing document or
 * an offline read must NEVER lock a user out — see [AppConfigRepository.refresh].
 */
data class AppConfig(
    val minVersionCode: Int = 1,
    val latestVersionCode: Int = 1,
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String = "",
    val hardModeEnabled: Boolean = true,
    val groupChallengeEnabled: Boolean = true,
    val updateUrl: String = "",
    // Remote stake limits. Hardcoded fallback defaults match docs/08 + docs/03
    // (Hard Mode €5–€100, Group buy-in €10–€50). A missing config NEVER breaks the
    // picker — these defaults apply.
    val hardModeMinStake: Int = 5,
    val hardModeMaxStake: Int = 100,
    val groupMinBuyIn: Int = 10,
    val groupMaxBuyIn: Int = 50
)

/**
 * Huawei-safe remote control. Reads the single `config/app` Firestore document using the
 * existing [FirebaseFirestore] instance (no Google Play Services dependency — works on
 * Huawei/no-GMS devices). The last good value is cached in SharedPreferences so the app
 * has a sensible config even when the very first read happens offline.
 */
@Singleton
class AppConfigRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadFromCache())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    /**
     * Reads `config/app` from Firestore and updates [config] + the local cache.
     *
     * FAIL-OPEN CONTRACT: on ANY failure (offline, Huawei/no-GMS, permission, missing doc)
     * this keeps the current cached/default config and returns it. It never throws and never
     * produces a blocking config from an error — a network problem must not lock the user out.
     */
    suspend fun refresh(): AppConfig {
        return try {
            val snapshot = firestore.collection("config").document("app").get().await()
            if (!snapshot.exists()) {
                Timber.d("AppConfig: config/app missing — using safe defaults")
                // Keep whatever we have cached (or defaults). Do not overwrite cache with a
                // blank doc, but defaults are already fail-safe.
                return _config.value
            }

            val current = _config.value
            // Read each field defensively; fall back to the current value (cache/default)
            // for any field the admin hasn't set yet — never to a hard-blocking value.
            val fetched = AppConfig(
                minVersionCode        = (snapshot.getLong("minVersionCode") ?: current.minVersionCode.toLong()).toInt(),
                latestVersionCode     = (snapshot.getLong("latestVersionCode") ?: current.latestVersionCode.toLong()).toInt(),
                maintenanceMode       = snapshot.getBoolean("maintenanceMode") ?: false,
                maintenanceMessage    = snapshot.getString("maintenanceMessage") ?: "",
                hardModeEnabled       = snapshot.getBoolean("hardModeEnabled") ?: true,
                groupChallengeEnabled = snapshot.getBoolean("groupChallengeEnabled") ?: true,
                updateUrl             = snapshot.getString("updateUrl") ?: "",
                hardModeMinStake      = (snapshot.getLong("hardModeMinStake") ?: current.hardModeMinStake.toLong()).toInt(),
                hardModeMaxStake      = (snapshot.getLong("hardModeMaxStake") ?: current.hardModeMaxStake.toLong()).toInt(),
                groupMinBuyIn         = (snapshot.getLong("groupMinBuyIn") ?: current.groupMinBuyIn.toLong()).toInt(),
                groupMaxBuyIn         = (snapshot.getLong("groupMaxBuyIn") ?: current.groupMaxBuyIn.toLong()).toInt()
            )

            saveToCache(fetched)
            _config.value = fetched
            Timber.d("AppConfig refreshed: $fetched")
            fetched
        } catch (e: Exception) {
            // FAIL OPEN — never block the user because of a network/Firestore error.
            Timber.w(e, "AppConfig refresh failed — using cached/default config (fail-open)")
            _config.value
        }
    }

    private fun loadFromCache(): AppConfig {
        val defaults = AppConfig()
        return AppConfig(
            minVersionCode        = prefs.getInt(KEY_MIN_VERSION, defaults.minVersionCode),
            latestVersionCode     = prefs.getInt(KEY_LATEST_VERSION, defaults.latestVersionCode),
            maintenanceMode       = prefs.getBoolean(KEY_MAINTENANCE_MODE, defaults.maintenanceMode),
            maintenanceMessage    = prefs.getString(KEY_MAINTENANCE_MSG, defaults.maintenanceMessage) ?: "",
            hardModeEnabled       = prefs.getBoolean(KEY_HARD_MODE_ENABLED, defaults.hardModeEnabled),
            groupChallengeEnabled = prefs.getBoolean(KEY_GROUP_ENABLED, defaults.groupChallengeEnabled),
            updateUrl             = prefs.getString(KEY_UPDATE_URL, defaults.updateUrl) ?: "",
            hardModeMinStake      = prefs.getInt(KEY_HARD_MIN_STAKE, defaults.hardModeMinStake),
            hardModeMaxStake      = prefs.getInt(KEY_HARD_MAX_STAKE, defaults.hardModeMaxStake),
            groupMinBuyIn         = prefs.getInt(KEY_GROUP_MIN_BUYIN, defaults.groupMinBuyIn),
            groupMaxBuyIn         = prefs.getInt(KEY_GROUP_MAX_BUYIN, defaults.groupMaxBuyIn)
        )
    }

    private fun saveToCache(config: AppConfig) {
        prefs.edit()
            .putInt(KEY_MIN_VERSION, config.minVersionCode)
            .putInt(KEY_LATEST_VERSION, config.latestVersionCode)
            .putBoolean(KEY_MAINTENANCE_MODE, config.maintenanceMode)
            .putString(KEY_MAINTENANCE_MSG, config.maintenanceMessage)
            .putBoolean(KEY_HARD_MODE_ENABLED, config.hardModeEnabled)
            .putBoolean(KEY_GROUP_ENABLED, config.groupChallengeEnabled)
            .putString(KEY_UPDATE_URL, config.updateUrl)
            .putInt(KEY_HARD_MIN_STAKE, config.hardModeMinStake)
            .putInt(KEY_HARD_MAX_STAKE, config.hardModeMaxStake)
            .putInt(KEY_GROUP_MIN_BUYIN, config.groupMinBuyIn)
            .putInt(KEY_GROUP_MAX_BUYIN, config.groupMaxBuyIn)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "detox_app_config"
        private const val KEY_MIN_VERSION = "minVersionCode"
        private const val KEY_LATEST_VERSION = "latestVersionCode"
        private const val KEY_MAINTENANCE_MODE = "maintenanceMode"
        private const val KEY_MAINTENANCE_MSG = "maintenanceMessage"
        private const val KEY_HARD_MODE_ENABLED = "hardModeEnabled"
        private const val KEY_GROUP_ENABLED = "groupChallengeEnabled"
        private const val KEY_UPDATE_URL = "updateUrl"
        private const val KEY_HARD_MIN_STAKE = "hardModeMinStake"
        private const val KEY_HARD_MAX_STAKE = "hardModeMaxStake"
        private const val KEY_GROUP_MIN_BUYIN = "groupMinBuyIn"
        private const val KEY_GROUP_MAX_BUYIN = "groupMaxBuyIn"
    }
}
