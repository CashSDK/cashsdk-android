package com.cashsdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small SharedPreferences wrapper that encrypts values with an AES-GCM key held in the
 * Android Keystore (hardware-backed where the device offers it).
 *
 * Used for the identity record — specifically `userToken`, the backend-minted bearer
 * credential the API trusts in production. It has to survive a relaunch: without it, every
 * identified call after a restart 401s silently (the SDK restored `userId` but not the token
 * that proves it), so entitlements never refresh and purchases verify unattributed. Persisting
 * a bearer token in plaintext preferences is not acceptable, hence this.
 *
 * Dependency-minimal on purpose: `androidx.security:security-crypto` would do the same job but
 * this module ships no AndroidX security dependency, and the Keystore API is available from
 * API 23 (the module's `minSdk` is 24).
 *
 * Fails soft. If the Keystore is unavailable or a key was invalidated (a device-credential
 * change wipes keys on some OEMs), reads return `null` and writes fall back to storing the
 * value unencrypted rather than throwing into the host app — the ciphertext marker prefix keeps
 * the two cases distinguishable.
 */
internal class SecureStore(context: Context, name: String) {

    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun getString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (!raw.startsWith(CIPHER_PREFIX)) return raw
        return decrypt(raw.removePrefix(CIPHER_PREFIX))
    }

    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(value)
        val stored = if (encrypted != null) CIPHER_PREFIX + encrypted else value
        prefs.edit().putString(key, stored).apply()
    }

    fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    // ── Crypto ────────────────────────────────────────────────────────────────

    private fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // iv || ciphertext — the GCM IV is 12 bytes and is not secret.
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val body = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: the SDK must be able to refresh
                // entitlements from a background launch, before the device is unlocked.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cashsdk_identity_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHER_PREFIX = "v1:"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
