package org.ghostcloak.app.access

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal class DurableInactivityStore(context: Context) : InactivityStore {
    private val file = File(context.noBackupFilesDir, "inactive-protection.v1")
    private val atomic = AtomicFile(file)

    override fun read(): InactivityRecord? {
        if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val bounded = ByteArray(45)
            var size = 0
            while (size < bounded.size) {
                val count = input.read(bounded, size, bounded.size - size)
                if (count < 0) break
                size += count
            }
            require(size == 40 && input.read() == -1)
            bounded.copyOf(size)
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x47434950 && input.readInt() == 1)
            val days = input.readInt()
            val period = InactivityPeriod.entries.first { it.days == days && it != InactivityPeriod.OFF }
            val boot = input.readInt()
            val elapsed = input.readLong()
            val wall = input.readLong()
            val high = input.readLong()
            require(elapsed >= 0 && wall > 0 && high >= wall && input.available() == 0)
            InactivityRecord(period, boot, elapsed, wall, high)
        }
    }

    override fun write(record: InactivityRecord) {
        require(record.period != InactivityPeriod.OFF)
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x47434950); out.writeInt(1); out.writeInt(record.period.days)
            out.writeInt(record.boot); out.writeLong(record.authenticatedElapsed)
            out.writeLong(record.authenticatedWall); out.writeLong(record.highestWall)
        } }.toByteArray()
        val output = atomic.startWrite()
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
            syncDirectory()
            check(read() == record)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    override fun clear() {
        for (candidate in listOf(File(file.path + ".new"), File(file.path + ".bak"), file))
            if (candidate.exists()) check(candidate.delete())
        syncDirectory()
        check(read() == null)
    }

    private fun syncDirectory() {
        val fd = android.system.Os.open(file.parentFile!!.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}

internal fun androidInactivityClock(context: Context): InactivityObservation = InactivityObservation(
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
    SystemClock.elapsedRealtime(), System.currentTimeMillis())
