package app.dcrux.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android: the key is generated inside the Android Keystore and cannot be exported, so the
 * ciphertext is useless off this device. Backups and device transfer are disabled in the
 * manifest, which means the secrets live exactly as long as the app's data on this phone.
 */
class AndroidSecureStore(context: Context) : SecureStore() {
    private val prefs = context.getSharedPreferences("dcrux_secure", Context.MODE_PRIVATE)

    override fun key(): SecretKey {
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

    override fun read(name: String): String? = prefs.getString(name, null)
    override fun write(name: String, value: String?) = prefs.edit().apply { if (value == null) remove(name) else putString(name, value) }.apply()
    override fun has(name: String): Boolean = prefs.contains(name)
    override fun clearAll() = prefs.edit().clear().apply()

    override fun wipe() {
        super.wipe()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "dcrux_master_v1"
    }
}
