package app.dcrux.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Secrets at rest. Everything is AES-256-GCM encrypted with a key that is generated
 * inside the Android Keystore and cannot be exported, so the ciphertext is useless
 * off this device. Backups and device transfer are disabled in the manifest, which
 * means the API key lives exactly as long as the app's data on this phone.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("dcrux_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    /** Returns null when the value cannot be decrypted (wrong device, wiped keystore). */
    @Synchronized
    fun decrypt(encoded: String): String? = try {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    // --- Claude API key ---------------------------------------------------------------

    fun saveApiKey(apiKey: String) = prefs.edit().putString(K_API, encrypt(apiKey.trim())).apply()

    fun apiKey(): String? = prefs.getString(K_API, null)?.let(::decrypt)

    fun hasApiKey(): Boolean = prefs.contains(K_API)

    fun clearApiKey() = prefs.edit().remove(K_API).apply()

    /** Safe to show on screen: never more than the prefix and the last four characters. */
    fun maskedApiKey(): String? = apiKey()?.let { k ->
        if (k.length < 12) "••••" else k.take(7) + "••••••••" + k.takeLast(4)
    }

    // --- App-lock PIN -----------------------------------------------------------------

    fun hasPin(): Boolean = prefs.contains(K_PIN)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val record = Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(hashPin(pin, salt), Base64.NO_WRAP)
        prefs.edit().putString(K_PIN, encrypt(record)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val record = prefs.getString(K_PIN, null)?.let(::decrypt) ?: return false
        val parts = record.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val expected = Base64.decode(parts[1], Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, hashPin(pin, salt))
    }

    fun clearPin() = prefs.edit().remove(K_PIN).apply()

    private fun hashPin(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)).encoded

    /** Used by "Reset app": drops every secret and the keystore key itself. */
    fun wipe() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "dcrux_master_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val K_API = "claude_api_key"
        const val K_PIN = "app_pin"
    }
}
