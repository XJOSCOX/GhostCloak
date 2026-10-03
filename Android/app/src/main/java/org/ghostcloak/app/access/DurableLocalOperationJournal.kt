package org.ghostcloak.app.access

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Credential encrypted, excluded from backup, independent of DB/Keystore/account credentials.
 * AtomicFile syncs the output before rename; all use is serialized by LocalOperationGate.
 * NONE is represented only by absence, never accepted from a populated journal. */
internal class DurableLocalOperationJournal(context: Context) : LocalOperationJournal {
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
            output.write(encode(state))
            atomic.finishWrite(output)
            val directory = android.system.Os.open(file.parentFile!!.path,
                android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
            check(read() == state)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    companion object {
        internal fun encode(state: LocalOperationState) = "GCLO1:${state.name}\n".toByteArray(Charsets.US_ASCII)
        internal fun decode(bytes: ByteArray): LocalOperationState = LocalOperationState.entries
            .filter { it !in setOf(LocalOperationState.NONE, LocalOperationState.CORRUPT) }
            .firstOrNull { encode(it).contentEquals(bytes) } ?: LocalOperationState.CORRUPT
    }
}
