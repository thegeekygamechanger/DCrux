package app.dcrux.data

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Secrets at rest. Everything is AES-256-GCM encrypted with a key that never leaves the device:
 * on Android it is generated inside the Keystore and cannot be exported; on a computer it is kept
 * in a file only the user's own account can read. Where the key and the small encrypted values
 * are kept is the platform's business; everything else is the same.
 */
abstract class SecureStore {
    protected abstract fun key(): SecretKey
    protected abstract fun read(name: String): String?
    protected abstract fun write(name: String, value: String?)
    protected abstract fun has(name: String): Boolean
    protected abstract fun clearAll()

    @Synchronized
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return (cipher.iv + ct).toByteString().base64()
    }

    /** Returns null when the value cannot be decrypted (wrong device, wiped key). */
    @Synchronized
    fun decrypt(encoded: String): String? = try {
        val raw = encoded.decodeBase64()!!.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    // --- Claude API key ---------------------------------------------------------------

    fun saveApiKey(apiKey: String) = write(K_API, encrypt(apiKey.trim()))
    fun apiKey(): String? = read(K_API)?.let(::decrypt)
    fun hasApiKey(): Boolean = has(K_API)
    fun clearApiKey() = write(K_API, null)

    /** Safe to show on screen: never more than the prefix and the last four characters. */
    fun maskedApiKey(): String? = apiKey()?.let { k ->
        if (k.length < 12) "••••" else k.take(7) + "••••••••" + k.takeLast(4)
    }

    /** A random id made once per install. It identifies nothing about the person or the device. */
    fun deviceId(): String = read(K_DEVICE) ?: java.util.UUID.randomUUID().toString().replace("-", "").also { write(K_DEVICE, it) }

    // --- Sync across devices ----------------------------------------------------------

    /** The pairing secret shared by this person's devices, or null when sync is off. Stored encrypted. */
    fun syncSecret(): String? = read(K_SYNC)?.let(::decrypt)
    fun setSyncSecret(secret: String?) = write(K_SYNC, secret?.let(::encrypt))

    // --- App-lock PIN -----------------------------------------------------------------

    fun hasPin(): Boolean = has(K_PIN)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val record = salt.toByteString().base64() + ":" + hashPin(pin, salt).toByteString().base64()
        write(K_PIN, encrypt(record))
    }

    fun checkPin(pin: String): Boolean {
        val record = read(K_PIN)?.let(::decrypt) ?: return false
        val parts = record.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].decodeBase64()?.toByteArray() ?: return false
        val expected = parts[1].decodeBase64()?.toByteArray() ?: return false
        return MessageDigest.isEqual(expected, hashPin(pin, salt))
    }

    fun clearPin() = write(K_PIN, null)

    private fun hashPin(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)).encoded

    /** Used by "Reset app": drops every secret and the key itself. */
    open fun wipe() = clearAll()

    protected companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val K_API = "claude_api_key"
        const val K_PIN = "app_pin"
        const val K_DEVICE = "install_id"
        const val K_SYNC = "sync_secret"
    }
}
