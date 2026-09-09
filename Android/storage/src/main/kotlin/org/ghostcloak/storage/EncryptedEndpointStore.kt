package org.ghostcloak.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.os.Build
import android.util.AtomicFile
import androidx.room.*
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Callable
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "endpoint_records")
data class SecretRecord(@PrimaryKey val name: String, val value: ByteArray) {
    override fun toString() = "SecretRecord(redacted)"
}
@Dao
interface RecordDao {
    @Query("SELECT value FROM endpoint_records WHERE name = :name") fun read(name: String): ByteArray?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(record: SecretRecord)
    @Query("DELETE FROM endpoint_records WHERE name = :name") fun remove(name: String)
    @Query("SELECT name FROM endpoint_records") fun keys(): List<String>
}
@Database(entities = [SecretRecord::class], version = 1, exportSchema = true)
abstract class EndpointDatabase : RoomDatabase() { abstract fun records(): RecordDao }

enum class KeyProtection { HARDWARE, SOFTWARE }

/** Construct off-main. One open store/engine per endpoint; caller closes when done. */
class EncryptedEndpointStore private constructor(private val db: EndpointDatabase,
    val protection: KeyProtection, private val databaseSecret: ByteArray) : EndpointRecords, AutoCloseable {
    override fun <T> transaction(block: () -> T): T = try { db.runInTransaction(Callable { block() }) }
        catch (e: android.database.sqlite.SQLiteException) { throw EndpointStorageFailure() }
    override fun read(key: String) = db.records().read(key)
    override fun write(key: String, value: ByteArray) {
        val copy = value.copyOf()
        try { db.records().put(SecretRecord(key, copy)) } finally { copy.fill(0) }
    }
    override fun remove(key: String) = db.records().remove(key)
    override fun keys(prefix: String) = db.records().keys().filter { it.startsWith(prefix) }
    override fun close() { try { db.close() } finally { databaseSecret.fill(0) } }

    companion object {
        // API 30 has no securityLevel replacement; keep suppression limited to this fallback.
        @Suppress("DEPRECATION")
        private fun legacyHardwareBacked(info: KeyInfo): Boolean = info.isInsideSecureHardware

        @Synchronized fun open(context: Context, endpoint: String): EncryptedEndpointStore {
            return try { openInternal(context, endpoint) }
            catch (e: java.security.GeneralSecurityException) { throw EndpointStorageFailure() }
            catch (e: java.io.IOException) { throw EndpointStorageFailure() }
            catch (e: android.database.sqlite.SQLiteException) { throw EndpointStorageFailure() }
        }
        private fun openInternal(context: Context, endpoint: String): EncryptedEndpointStore {
            require(endpoint.matches(Regex("[a-z0-9-]{1,40}")))
            val alias = "ghost-cloak.db.$endpoint"
            val file = File(context.noBackupFilesDir, "$endpoint.wrapped")
            val databaseFile = File(context.noBackupFilesDir, "$endpoint.db")
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keystore.containsAlias(alias) && (!file.exists() || !databaseFile.exists())) {
                // Includes interrupted initialization; recovery must be explicit, never identity replacement.
                throw EndpointStorageFailure()
            }
            // Never silently regenerate a missing key for existing state.
            if (!keystore.containsAlias(alias)) {
                if (file.exists() || databaseFile.exists()) throw EndpointStorageFailure()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    generateKey()
                }
            }
            val key = keystore.getKey(alias, null) as SecretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel in listOf(
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                    KeyProperties.SECURITY_LEVEL_STRONGBOX
                )
            } else {
                legacyHardwareBacked(info)
            }
            val aad = alias.toByteArray(Charsets.UTF_8)
            val atomic = AtomicFile(file)
            val secret = if (file.exists()) {
                val wrapped = atomic.readFully()
                if (wrapped.size != 61 || wrapped[0] != 1.toByte()) throw EndpointStorageFailure()
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(1, 13)))
                    updateAAD(aad)
                    doFinal(wrapped.copyOfRange(13, wrapped.size))
                }
            } else {
                if (databaseFile.exists()) throw EndpointStorageFailure()
                ByteArray(32).also { bytes ->
                    var saved = false
                    try {
                        SecureRandom().nextBytes(bytes)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, key)
                        cipher.updateAAD(aad)
                        val wrapped = byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
                        val output = atomic.startWrite()
                        var finished = false
                        try { output.write(wrapped); atomic.finishWrite(output); finished = true }
                        finally { if (!finished) atomic.failWrite(output) }
                        saved = true
                    } finally { if (!saved) bytes.fill(0) }
                }
            }
            var opened = false
            try {
                System.loadLibrary("sqlcipher")
                net.zetetic.database.Logger.setTarget(net.zetetic.database.NoopTarget())
                val factory = SupportOpenHelperFactory(secret)
                val db = Room.databaseBuilder(context.applicationContext, EndpointDatabase::class.java, databaseFile.absolutePath)
                    .openHelperFactory(factory).build()
                try { db.openHelper.writableDatabase; opened = true }
                finally { if (!opened) db.close() }
                return EncryptedEndpointStore(db, if (hardware) KeyProtection.HARDWARE else KeyProtection.SOFTWARE, secret)
            } finally { if (!opened) secret.fill(0) }
        }
    }
}
