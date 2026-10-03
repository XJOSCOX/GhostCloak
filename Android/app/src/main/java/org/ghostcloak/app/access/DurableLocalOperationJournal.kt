package org.ghostcloak.app.access

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal enum class JournalCheckpoint { WRITE, FILE_SYNC, DIRECTORY_SYNC, READBACK }

/** Credential encrypted, excluded from backup, independent of DB/Keystore/account credentials.
 * AtomicFile syncs the output before rename; all use is serialized by LocalOperationGate.
 * NONE is represented only by absence, never accepted from a populated journal. */
internal class DurableLocalOperationJournal(context: Context,
    private val checkpoint: (JournalCheckpoint) -> Unit = {},
) : LocalOperationJournal {
    private val file = File(context.noBackupFilesDir, "local-operation.v1")
    private val atomic = AtomicFile(file)
    override fun read(): LocalOperationState {
        return try {
            if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
                LocalOperationState.NONE
            else {
                val bytes = atomic.openRead().use { input ->
                    val buffer = ByteArray(65)
                    var size = 0
                    while (size < buffer.size) {
                        val count = input.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    buffer.copyOf(size)
                }
                decode(bytes)
            }
        } catch (_: Exception) { LocalOperationState.CORRUPT }
    }
    override fun write(state: LocalOperationState) {
        require(state !in setOf(LocalOperationState.NONE, LocalOperationState.CORRUPT))
        val output = atomic.startWrite()
        try {
            checkpoint(JournalCheckpoint.WRITE)
            output.write(encode(state))
            checkpoint(JournalCheckpoint.FILE_SYNC)
            // AtomicFile can log a failed sync rather than throw on some Android versions.
            // Explicit checked fsync must succeed before a transition can be authorized.
            output.fd.sync()
            atomic.finishWrite(output)
            val directory = android.system.Os.open(file.parentFile!!.path,
                android.system.OsConstants.O_RDONLY, 0)
            try { checkpoint(JournalCheckpoint.DIRECTORY_SYNC); android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
            checkpoint(JournalCheckpoint.READBACK)
            check(read() == state)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    override fun clearCompleted() {
        check(read()==LocalOperationState.COMPLETE)
        // Baseline was already verified; preserve COMPLETE across crashes until checked removal.
        for(candidate in listOf(File(file.path+".new"),File(file.path+".bak"),file)) {
            if(candidate.exists()) check(candidate.delete()) {"journal_clear_failed"}
        }
        val directory=android.system.Os.open(file.parentFile!!.path,android.system.OsConstants.O_RDONLY,0)
        try {android.system.Os.fsync(directory)} finally {android.system.Os.close(directory)}
        check(read()==LocalOperationState.NONE)
    }
    companion object {
        internal fun encode(state: LocalOperationState) = "GCLO1:${state.name}\n".toByteArray(Charsets.US_ASCII)
        internal fun decode(bytes: ByteArray): LocalOperationState = LocalOperationState.entries
            .filter { it !in setOf(LocalOperationState.NONE, LocalOperationState.CORRUPT) }
            .firstOrNull { encode(it).contentEquals(bytes) } ?: LocalOperationState.CORRUPT
    }
}
