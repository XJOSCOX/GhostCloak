package org.ghostcloak.app.application

import android.os.SystemClock
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.transport.FetchCooldown

/** No wall-clock dependency. After reboot conservatively wait the saved delay again. */
internal fun storedFetchCooldown(records: EndpointRecords, audience: String, boot: Int,
    clock: () -> Long = { SystemClock.elapsedRealtime() }): FetchCooldown {
    val name = "app/fetch-cooldown/$audience"
    val cooldown = FetchCooldown(clock)
    records.transaction {
        records.read(name)?.decodeToString()?.let { encoded ->
            val values = encoded.split(':').map { it.toLongOrNull() ?: throw EndpointStorageFailure() }
            if (values.size != 4 || values.any { it < 0 }) throw EndpointStorageFailure()
            val deadline = if (values[0] == boot.toLong()) values[1] else clock() + values[2].coerceAtMost(Long.MAX_VALUE - clock())
            cooldown.restore(deadline, values[3].coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
    cooldown.checkpoint = { deadline, count ->
        records.transaction {
            if (deadline == 0L) records.remove(name)
            else records.write(name, "$boot:$deadline:${(deadline - clock()).coerceAtLeast(0)}:$count".toByteArray())
        }
    }
    if (cooldown.remainingMillis > 0) {
        // Rebase after reboot before another process can reopen the checkpoint.
        records.transaction {
            val count = records.read(name)?.decodeToString()?.substringAfterLast(':')?.toIntOrNull() ?: 0
            cooldown.checkpoint(clock() + cooldown.remainingMillis, count)
        }
    }
    return cooldown
}
