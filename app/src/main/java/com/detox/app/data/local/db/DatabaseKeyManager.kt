package com.detox.app.data.local.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the SQLCipher database passphrase.
 *
 * The passphrase is a random 32-byte secret. It is NEVER stored in plaintext: it is wrapped
 * (AES/GCM-encrypted) by a key that lives in the Android Keystore (`AndroidKeyStore`) — the
 * wrapping key never leaves secure hardware (hardware-backed on most devices). Only the
 * encrypted passphrase + IV are kept in SharedPreferences. The Keystore is part of AOSP, so this
 * works on Huawei devices without Google Play Services.
 *
 * Graceful invalidation: if the Keystore key is lost/invalidated (e.g. lock-screen or biometric
 * changes on some OEMs), decryption fails. We then regenerate a fresh passphrase and signal
 * [Passphrase.wasReset] = true so the caller can drop the (now-unreadable) encrypted DB and let
 * Firestore re-sync repopulate it — the app must NEVER crash because of a lost key.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** @property wasReset true when the passphrase had to be regenerated because the previously
     *  stored one could no longer be decrypted (Keystore key lost) — the existing encrypted DB is
     *  unreadable and must be discarded. */
    data class Passphrase(val bytes: ByteArray, val wasReset: Boolean)

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns the DB passphrase, generating + wrapping a new one on first run or after key loss. */
    fun getOrCreatePassphrase(): Passphrase {
        val encB64 = prefs.getString(KEY_ENC_PASSPHRASE, null)
        val ivB64 = prefs.getString(KEY_ENC_IV, null)

        if (encB64 != null && ivB64 != null) {
            try {
                val key = loadKeystoreKey()
                if (key != null) {
                    val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                    val enc = Base64.decode(encB64, Base64.NO_WRAP)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    return Passphrase(cipher.doFinal(enc), wasReset = false)
                }
                Timber.w("DatabaseKeyManager: Keystore entry missing — regenerating DB passphrase")
            } catch (e: Exception) {
                // KeyPermanentlyInvalidatedException, AEADBadTagException, etc.
                Timber.w(e, "DatabaseKeyManager: stored DB passphrase undecryptable — regenerating")
            }
            // Invalidation path: a previously encrypted DB exists but is now unreadable.
            return Passphrase(regenerate(), wasReset = true)
        }

        // First run — no stored passphrase yet; nothing to discard.
        return Passphrase(regenerate(), wasReset = false)
    }

    /** Wipes the wrapped passphrase and Keystore key (used by logout / hard reset paths). */
    fun clear() {
        prefs.edit().remove(KEY_ENC_PASSPHRASE).remove(KEY_ENC_IV).apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }.onFailure { Timber.w(it, "DatabaseKeyManager: failed to delete Keystore entry") }
    }

    private fun regenerate(): ByteArray {
        // Drop any stale/invalidated Keystore entry first, then mint a fresh wrapping key.
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, createKeystoreKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(passphrase)
        prefs.edit()
            .putString(KEY_ENC_PASSPHRASE, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(KEY_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        Timber.d("DatabaseKeyManager: generated a new Keystore-wrapped DB passphrase")
        return passphrase
    }

    private fun loadKeystoreKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun createKeystoreKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Intentionally NOT setUserAuthenticationRequired(true): the DB must be readable by
            // background workers (DailyEvaluationWorker, etc.) without a screen unlock, and
            // auth-bound keys are the ones prone to invalidation on lock-screen/biometric changes.
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "detox_db_security"
        private const val KEY_ENC_PASSPHRASE = "enc_passphrase"
        private const val KEY_ENC_IV = "enc_iv"
        private const val KEY_ALIAS = "detox_db_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
    }
}
