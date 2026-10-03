package org.ghostcloak.app.access

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.security.KeyStore

/** No record reads, registration, crypto generation or networking. Coordinator alone owns this. */
internal interface DestructionKeys {
    fun aliases(): List<String>
    fun delete(alias: String)
    fun absent(alias: String): Boolean
}
internal class AndroidDestructionKeys : DestructionKeys {
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    override fun aliases()=store().aliases().toList()
    override fun delete(alias: String) {store().deleteEntry(alias)}
    override fun absent(alias: String): Boolean {
        val keys=store()
        return !keys.containsAlias(alias) && keys.getKey(alias,null)==null && keys.getCertificate(alias)==null
    }
}
internal enum class DestructionCheckpoint { KEY_DELETED, KEYS_VERIFIED, FILE_DELETED, STORAGE_VERIFIED, FRESH_VERIFIED }
internal class AndroidLocalDestruction(
    private val context: Context,
    private val keys: DestructionKeys=AndroidDestructionKeys(),
    private val checkpoint: (DestructionCheckpoint)->Unit={},
    private val deleteFile: (File)->Boolean={it.delete()},
) : LocalDestruction {
    companion object {
        internal fun ownedAlias(alias: String): Boolean =
            Regex("ghost-cloak\\.db\\.[a-z0-9-]{1,40}").matches(alias) ||
            Regex("ghostcloak\\.auth\\.[0-9a-f]{64}\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(alias)
        private fun reserved(file: File)=file.name in setOf("local-operation.v1","local-operation.v1.bak","local-operation.v1.new") ||
            Regex("androidx\\.work\\.workdb(?:-wal|-shm|-journal)?").matches(file.name)
    }
    private fun ownedKeys(): List<String> = keys.aliases().also {all->
        // Unknown entries under our exact namespaces cannot be safely classified or silently ignored.
        check(all.none { (it.startsWith("ghost-cloak.db.") || it.startsWith("ghostcloak.auth.")) && !ownedAlias(it) }) {"unclassified_owned_key"}
    }.filter(::ownedAlias).sortedWith(compareBy({!it.startsWith("ghost-cloak.db.")},{it}))
    override suspend fun destroyKeys() {
        for(alias in ownedKeys()) {
            if(!keys.absent(alias)) keys.delete(alias)
            check(keys.absent(alias)) {"critical_key_remains"}
            checkpoint(DestructionCheckpoint.KEY_DELETED)
        }
        check(ownedKeys().isEmpty()) {"critical_key_remains"}
        checkpoint(DestructionCheckpoint.KEYS_VERIFIED)
    }
    private fun children(directory: File): List<File> {
        if(!directory.exists()) return emptyList()
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {"invalid_owned_root"}
        return directory.listFiles()?.toList() ?: error("inventory_unavailable")
    }
    private fun targets(): List<File> =
        children(context.noBackupFilesDir).filterNot(::reserved) + children(context.filesDir) + children(context.cacheDir) +
        children(context.getDatabasePath("safe-exit-inventory").parentFile!!).filterNot {Regex("androidx\\.work\\.workdb(?:-wal|-shm|-journal)?").matches(it.name)}
    private fun sync(directory: File) {
        val fd=android.system.Os.open(directory.path,android.system.OsConstants.O_RDONLY,0)
        try {android.system.Os.fsync(fd)} finally {android.system.Os.close(fd)}
    }
    private fun remove(file: File) {
        // Never traverse a symbolic link; unlinking our directory entry cannot delete another app's copy.
        if(file.isDirectory && !Files.isSymbolicLink(file.toPath())) children(file).forEach(::remove)
        if(Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) check(deleteFile(file)) {"owned_file_remains"}
        sync(file.parentFile!!)
        checkpoint(DestructionCheckpoint.FILE_DELETED)
    }
    override suspend fun cleanupStorage() {
        check(ownedKeys().isEmpty()) {"keys_not_destroyed"}
        context.revokeUriPermission(android.net.Uri.parse("content://${context.packageName}.attachment-view/"),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        var failed=false
        // Finish other artifacts after one deletion failure; retry retains the closed fence.
        for(file in targets()) try {remove(file)} catch(_: Exception) {failed=true}
        for(name in listOf("appearance","notification-permission")) {
            if(!context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()) failed=true
        }
        check(!failed && targets().isEmpty()) {"cleanup_incomplete"}
        checkpoint(DestructionCheckpoint.STORAGE_VERIFIED)
    }
    override suspend fun verifyFresh() {
        check(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty()) {"notification_remains"}
        check(ownedKeys().isEmpty() && targets().isEmpty()) {"fresh_baseline_unverified"}
        check(listOf("appearance","notification-permission").all {context.getSharedPreferences(it,Context.MODE_PRIVATE).all.isEmpty()}) {"preferences_remain"}
        checkpoint(DestructionCheckpoint.FRESH_VERIFIED)
    }
}
