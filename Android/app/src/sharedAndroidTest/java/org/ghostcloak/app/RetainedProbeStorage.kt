package org.ghostcloak.app

import android.content.*
import android.os.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File

internal class RetainedProbeStorage private constructor(
    private val context: Context, private val connection: ServiceConnection, private val binder: IBinder,
) : AutoCloseable {
    private fun <T> request(code: Int, name: String? = null, writing: Boolean = false,
                            read: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RetainedProbeService.TOKEN)
            if (name != null) { check(name in RetainedProbeService.NAMES); data.writeString(name) }
            if (code == RetainedProbeService.OPEN) data.writeInt(if (writing) 1 else 0)
            check(binder.transact(code, data, reply, 0)); reply.readException()
            return read(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun descriptor(name: String, writing: Boolean) = request(
        RetainedProbeService.OPEN, name, writing) { checkNotNull(it.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)) }
    fun exists(name: String) = request(RetainedProbeService.EXISTS, name) { it.readInt() == 1 }
    fun copyFrom(name: String, source: File) {
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor(name, true)).use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }
    fun bytes(name: String): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(descriptor(name, false)).use { it.readBytes() }
    fun write(name: String, bytes: ByteArray) = ParcelFileDescriptor.AutoCloseOutputStream(descriptor(name, true)).use { it.write(bytes) }
    fun materializeDatabase(root: File): File {
        check(root.mkdirs() || root.isDirectory)
        for (name in listOf("local.db", "local.db-wal", "local.db-shm", "local.wrapped")) {
            if (exists(name)) File(root, name).writeBytes(bytes(name))
        }
        return root
    }
    fun clear() = request(RetainedProbeService.CLEAR) { Unit }
    override fun close() { context.unbindService(connection) }
    companion object {
        suspend fun open(context: Context): RetainedProbeStorage {
            val ready = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(service) }
                override fun onServiceDisconnected(name: ComponentName) { }
                override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("probe_service_unavailable")) }
            }
            val intent = Intent().setComponent(ComponentName("org.ghostcloak.app.test", RetainedProbeService::class.java.name))
            check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            try { return RetainedProbeStorage(context, connection, withTimeout(10_000) { ready.await() }) }
            catch (failure: Throwable) { context.unbindService(connection); throw failure }
        }
    }
}
