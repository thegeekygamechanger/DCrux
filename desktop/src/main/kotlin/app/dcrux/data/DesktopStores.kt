package app.dcrux.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** The app's database on a computer: one SQLite file in the app's folder, brought up to date step by step. */
fun openDesktopDb(dir: File): AppDb =
    Room.databaseBuilder<AppDb>(name = File(dir, "dcrux.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*DB_MIGRATIONS.map { (from, sql) ->
            object : Migration(from, from + 1) {
                override fun migrate(connection: SQLiteConnection) = sql.forEach { connection.execSQL(it) }
            }
        }.toTypedArray())
        .build()

fun desktopSettings(dir: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath { File(dir, "dcrux_settings.preferences_pb").toOkioPath() }

/**
 * A computer has no Keystore to lock a key into, so the key is a random 256-bit value in a file
 * that only the user's own account can read (owner-only permissions where the system has them).
 * Anyone who can read that account's files can read the key; the encryption protects the data
 * from other accounts and from casual copying, not from the account's owner or an administrator.
 */
class DesktopSecureStore(private val dir: File) : SecureStore() {
    private val keyFile = File(dir, "key.bin")
    private val file = File(dir, "secure.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun ownerOnly(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
            .onFailure { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
    }

    @Synchronized
    override fun key(): SecretKey {
        if (!keyFile.exists() || keyFile.length() != 32L) {
            dir.mkdirs()
            keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
            ownerOnly(keyFile)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    @Synchronized private fun save() { dir.mkdirs(); file.outputStream().use { props.store(it, null) }; ownerOnly(file) }
    @Synchronized override fun read(name: String): String? = props.getProperty(name)
    @Synchronized override fun write(name: String, value: String?) { if (value == null) props.remove(name) else props.setProperty(name, value); save() }
    @Synchronized override fun has(name: String): Boolean = props.containsKey(name)
    @Synchronized override fun clearAll() { props.clear(); save(); keyFile.delete() }
}
