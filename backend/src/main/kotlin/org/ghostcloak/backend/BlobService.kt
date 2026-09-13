package org.ghostcloak.backend

import org.ghostcloak.protocol.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.Semaphore

/** One service owns this directory. No plaintext or E2EE descriptor crosses this boundary. */
class BlobService(private val db: BackendDatabase, private val auth: MailboxService, root: File,
    private val clock: Clock = Clock.systemUTC(), private val minimumFreeBytes: Long = BlobPolicy.FREE_BYTES) : AutoCloseable {
    private val directory = root.canonicalFile
    private val lockFile: RandomAccessFile
    private val lock: java.nio.channels.FileLock
    private val transfers = Semaphore(4)
    init {
        require(root.isAbsolute && !Files.isSymbolicLink(root.toPath()))
        Files.createDirectories(directory.toPath()); permissions(directory, "rwx------")
        lockFile = RandomAccessFile(File(directory, ".owner"), "rw")
        lock = lockFile.channel.tryLock() ?: error("attachment_storage_busy")
        permissions(File(directory, ".owner"), "rw-------")
        // Exclusive directory ownership makes interrupted upload recovery safe across restarts.
        db.transaction { db.blobs.all().filter { it.uploading }.forEach {
            db.blobs.put(it.id, row(it, uploading = false))
        } }
        cleanup()
    }
    private fun now() = clock.millis()
    private fun permissions(file: File, value: String) {
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(value))
    }
    private fun file(id: String, partial: Boolean = false): File {
        requireApi(BlobPolicy.validId(id), "unavailable", 404)
        return File(directory, id + if (partial) ".partial" else "").also {
            requireApi(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == directory, "unavailable", 404)
        }
    }
    private fun row(r: BlobRow, expires: Long = r.expires, complete: Boolean = r.complete, uploading: Boolean = r.uploading) =
        BlobRow(r.id,r.owner,r.device,r.length,r.digest,r.capabilityHash,r.created,expires,complete,uploading)
    private fun result(r: BlobRow) = BlobResult(complete = r.complete, expiresAt = r.expires)
    // This committed budget is independent of operation rollback, including failed probing.
    private fun request(token: String): DeviceRow {
        val device = auth.authenticateSession(token)
        budget(device.accountId)
        return device
    }
    private fun budget(owner: String, upload: Long = 0, download: Long = 0, countRequest: Boolean = true) = db.transaction {
        val time = now(); val minute = time / 60000; val day = time / 86400000
        val old = db.blobBudgets.get(owner)
        val requests = (if (old?.minute == minute) old.requests else 0) + if (countRequest) 1 else 0
        val uploaded = (if (old?.day == day) old.upload else 0) + upload
        val downloaded = (if (old?.day == day) old.download else 0) + download
        requireApi(requests <= 10 && uploaded <= BlobPolicy.DAILY_UPLOAD && downloaded <= BlobPolicy.DAILY_DOWNLOAD, "rate_limited", 429)
        db.blobBudgets.put(owner, BlobBudget(owner,minute,requests,uploaded,downloaded,day))
    }
    fun reserve(token: String, reservation: BlobReservation): BlobResult {
        val device = request(token); reservation.validate()
        return db.transaction {
            val old = db.blobs.get(reservation.id)
            if (old != null) {
                requireApi(old.owner == device.accountId && old.device == device.id && old.length == reservation.length &&
                    MessageDigest.isEqual(old.digest,reservation.digest) && MessageDigest.isEqual(old.capabilityHash,reservation.capabilityHash), "conflict",409)
                requireApi(old.expires > now(), "unavailable",404)
                if(old.complete) requireApi(file(old.id).isFile && file(old.id).length()==old.length,"unavailable",404)
                return@transaction result(old)
            }
            val rows = db.blobs.all()
            requireApi(rows.size < 10000 && rows.sumOf { it.length } + reservation.length <= BlobPolicy.GLOBAL_BYTES, "capacity",429)
            val owned = rows.filter { it.owner == device.accountId }
            requireApi(owned.count { !it.complete } < 2 && owned.sumOf { it.length } + reservation.length <= BlobPolicy.ACCOUNT_BYTES, "capacity",429)
            requireApi(directory.usableSpace >= minimumFreeBytes + reservation.length, "capacity",503)
            budget(device.accountId, upload = reservation.length, countRequest = false)
            val r = BlobRow(reservation.id,device.accountId,device.id,reservation.length,reservation.digest.copyOf(),reservation.capabilityHash.copyOf(),now(),now()+BlobPolicy.PARTIAL_TTL)
            db.blobs.put(r.id,r); result(r)
        }
    }
    fun upload(token: String, id: String, input: InputStream, checkpoint: () -> Unit = {}): BlobResult {
        val device = request(token)
        requireApi(transfers.tryAcquire(), "busy",429)
        var claimed: BlobRow? = null
        try {
            val r = db.transaction {
                val current = db.blobs.get(id) ?: throw ApiFailure(404,"unavailable")
                requireApi(current.owner == device.accountId && current.device == device.id && current.expires > now(),"unavailable",404)
                requireApi(!current.uploading && db.blobs.all().none { it.owner == device.accountId && it.uploading },"busy",429)
                db.blobs.put(id,row(current,uploading=true)); current
            }
            claimed = r
            val partial = file(id,true)
            val hash = MessageDigest.getInstance("SHA-256")
            FileOutputStream(partial).use { output ->
                permissions(partial,"rw-------")
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    checkpoint(); val n = input.read(buffer); if (n < 0) break
                    total += n; requireApi(total <= r.length && total <= BlobPolicy.MAX_BYTES,"body_size",413)
                    output.write(buffer,0,n); hash.update(buffer,0,n)
                }
                requireApi(total == r.length && MessageDigest.isEqual(hash.digest(),r.digest),"invalid_blob")
                output.fd.sync()
            }
            // Authorization was checked before reading. Normal session expiry during a long
            // admitted request must not make every slow upload fail/restart indefinitely.
            checkpoint()
            return db.transaction {
                val current = db.blobs.get(id) ?: throw ApiFailure(404,"unavailable")
                requireApi(current.expires > now(),"unavailable",404)
                Files.move(partial.toPath(),file(id).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                if(Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix"))
                    java.nio.channels.FileChannel.open(directory.toPath(),StandardOpenOption.READ).use { it.force(true) }
                // A duplicate completed PUT never moves its retention deadline.
                val complete = row(current,expires=if(current.complete) current.expires else now()+BlobPolicy.COMPLETE_TTL,complete=true,uploading=false)
                db.blobs.put(id,complete); result(complete)
            }
        } finally {
            try { claimed?.let { r ->
                file(r.id,true).delete()
                db.transaction { db.blobs.get(r.id)?.let { db.blobs.put(r.id,row(it,uploading=false)) } }
            } } finally { transfers.release() }
        }
    }
    /** Caller closes stream to release the bounded download permit. */
    fun download(token: String, id: String, capability: String?): InputStream {
        val device = request(token)
        val validCapability = capability?.matches(Regex("[0-9a-f]{64}")) == true
        val candidate = if (validCapability)
            capability!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray() else ByteArray(32)
        val r = db.transaction {
            val found = db.blobs.get(id)
            val matches = MessageDigest.isEqual(DeviceAuth.digest(candidate),found?.capabilityHash ?: ByteArray(32))
            requireApi(found != null && found.complete && found.expires > now() && validCapability && matches,"unavailable",404)
            found!!
        }
        candidate.fill(0)
        requireApi(transfers.tryAcquire(),"busy",429)
        try {
            val path = file(id)
            requireApi(path.isFile && path.length() == r.length,"unavailable",404)
            budget(device.accountId,download=r.length,countRequest=false)
            val input = FileInputStream(path)
            return object : FilterInputStream(input) {
                private val closed = java.util.concurrent.atomic.AtomicBoolean()
                override fun close() { if (closed.compareAndSet(false,true)) { try { super.close() } finally { transfers.release() } } }
            }
        } catch (e: Throwable) { transfers.release(); throw e }
    }
    fun cleanup() {
        db.transaction {
            db.blobs.all().filter { it.expires <= now() && !it.uploading }.take(128).forEach {
                val body = file(it.id); val partial = file(it.id,true)
                if ((!body.exists() || body.delete()) && (!partial.exists() || partial.delete())) db.blobs.remove(it.id)
            }
        }
        // Bounded orphan reconciliation also handles rename-before-DB-commit crashes.
        Files.newDirectoryStream(directory.toPath()).use { paths ->
            var processed = 0
            for (path in paths) {
                if (++processed > 1024) break
                val name = path.fileName.toString(); val id = name.removeSuffix(".partial")
                if (!BlobPolicy.validId(id)) continue
                val row = db.transaction { db.blobs.get(id) }
                if (row == null || (!row.uploading && (name.endsWith(".partial") || !row.complete))) Files.deleteIfExists(path)
            }
        }
    }
    override fun close() { lock.release(); lockFile.close() }
}
