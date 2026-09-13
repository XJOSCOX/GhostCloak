package org.ghostcloak.attachments

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import java.io.*
import java.nio.file.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Serializable enum class TransferState { ENCRYPTED, RESERVED, UPLOADING, UPLOADED, DOWNLOADING, VERIFYING, READY, FAILED }
@Serializable class TransferEntry(val descriptor: AttachmentDescriptor, val state: TransferState,
    val upload: Boolean, val createdAt: Long, val references: Set<String> = emptySet()) {
    override fun toString()="TransferEntry(redacted)"
}

/** root MUST be in the endpoint's credential-encrypted noBackupFilesDir. No external/public paths. */
enum class AttachmentEvent { ATTACH_ENCRYPT_START, ATTACH_ENCRYPT_SUCCESS, ATTACH_UPLOAD_SUCCESS, ATTACH_UPLOAD_FAILED, ATTACH_DOWNLOAD_SUCCESS, ATTACH_DOWNLOAD_FAILED }
class AttachmentStore(private val records: EndpointRecords, root: File,
    private val diagnostics: ((AttachmentEvent)->Unit)? = null) {
    private fun emit(event:AttachmentEvent) { try { diagnostics?.invoke(event) } catch (_:Exception) {} }
    private val directory=root.canonicalFile
    private val generation=AtomicLong()
    private val mutex=Mutex()
    private val active=AtomicReference<Job?>()
    private suspend fun <T> transfer(block:suspend ()->T):T=withContext(Dispatchers.IO) {
        coroutineScope { mutex.withLock {
            val job=currentCoroutineContext()[Job]!!
            active.set(job)
            try { block() } finally { active.compareAndSet(job,null) }
        } }
    }
    private val uploads=File(directory,"upload")
    private val downloads=File(directory,"download")
    private val scratch=File(directory,"scratch")
    init {
        require(!Files.isSymbolicLink(root.toPath()))
        listOf(directory,uploads,downloads,scratch).forEach { require(it.mkdirs() || it.isDirectory) }
        invalidate()
        reconcile()
    }
    private fun key(id:String):String { require(AttachmentFormat.validId(id)); return "attachment/transfer/$id" }
    private fun file(parent:File,id:String):File { require(AttachmentFormat.validId(id)); return File(parent,id).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile==parent.canonicalFile)
    } }
    private fun save(entry:TransferEntry) = records.transaction {
        val bytes=NetworkCodec.encode(entry)
        try { records.write(key(entry.descriptor.id),bytes) } finally { bytes.fill(0) }
    }
    fun entry(id:String):TransferEntry? = records.transaction {
        records.read(key(id))?.let { bytes -> try { NetworkCodec.decode<TransferEntry>(bytes,8192).also { it.descriptor.validate() } } finally { bytes.fill(0) } }
    }
    private fun entries()=records.transaction { records.keys("attachment/transfer/").mapNotNull { entry(it.removePrefix("attachment/transfer/")) } }
    fun cachedReferences(): Set<String> = entries().filter {
        it.state in setOf(TransferState.READY,TransferState.UPLOADED) &&
            (file(downloads,it.descriptor.id).exists() || (it.upload && file(uploads,it.descriptor.id).exists()))
    }.flatMap { it.references }.toSet()
    private fun update(entry:TransferEntry,state:TransferState) = records.transaction {
        val current=this.entry(entry.descriptor.id) ?: error("attachment_unavailable")
        TransferEntry(entry.descriptor,state,current.upload,entry.createdAt,current.references).also(::save)
    }
    /** Lock/background/logout hook. Never permits stale transfer completion to publish plaintext. */
    fun invalidate() {
        generation.incrementAndGet()
        active.get()?.cancel(CancellationException("attachment_unavailable"))
        // Access revocation must succeed even if an OS-held fd postpones physical unlinking.
        scratch.listFiles()?.forEach { if(it.isFile) it.delete() }
    }
    private fun checkpoint(expected:Long,allowed:()->Boolean) {
        check(generation.get()==expected && allowed()) { "attachment_unavailable" }
    }
    suspend fun prepare(source:InputStream,length:Long,kind:AttachmentKind,seconds:Int,
        filename: String? = null, allowed:()->Boolean):AttachmentDescriptor=transfer {
        val epoch=generation.get(); checkpoint(epoch,allowed)
        require(entries().size<128)
        val temporary=file(uploads,AttachmentFormat.newId())
        val context=currentCoroutineContext()
        emit(AttachmentEvent.ATTACH_ENCRYPT_START)
        try {
            val raw=source.use { AttachmentFormat.encrypt(it,temporary,length,kind,seconds) { context.ensureActive(); checkpoint(epoch,allowed) } }
            val descriptor=AttachmentDescriptor(id=raw.id,capability=raw.capability,key=raw.key,digest=raw.digest,
                plaintextLength=raw.plaintextLength,paddedLength=raw.paddedLength,ciphertextLength=raw.ciphertextLength,
                kind=raw.kind,disappearingSeconds=raw.disappearingSeconds,
                filename=filename?.let(AttachmentFormat::sanitizeFilename)).also { it.validate() }
            checkpoint(epoch,allowed)
            Files.move(temporary.toPath(),file(uploads,descriptor.id).toPath(),StandardCopyOption.ATOMIC_MOVE)
            records.transaction { checkpoint(epoch,allowed); save(TransferEntry(descriptor,TransferState.ENCRYPTED,true,System.currentTimeMillis())) }
            emit(AttachmentEvent.ATTACH_ENCRYPT_SUCCESS)
            descriptor
        } finally { temporary.delete() }
    }
    suspend fun upload(id:String,client:BlobTransferClient,allowed:()->Boolean):AttachmentDescriptor=transfer {
        val epoch=generation.get(); checkpoint(epoch,allowed)
        var saved=entry(id) ?: error("attachment_missing"); require(saved.upload)
        try {
            val status=client.reserve(saved.descriptor); checkpoint(epoch,allowed)
            saved=update(saved,TransferState.RESERVED)
            if(!status.complete) { saved=update(saved,TransferState.UPLOADING); client.upload(saved.descriptor,file(uploads,id)) }
            records.transaction { checkpoint(epoch,allowed); check(entry(id)!=null); update(saved,TransferState.UPLOADED).descriptor }.also { emit(AttachmentEvent.ATTACH_UPLOAD_SUCCESS) }
        } catch(e:Throwable) { failed(saved); emit(AttachmentEvent.ATTACH_UPLOAD_FAILED); throw e }
    }
    /** Journal binding is local and encrypted. Server cannot distinguish referenced blobs from orphans. */
    fun bind(id:String,reference:String) {
        val saved=entry(id) ?: error("attachment_missing")
        require(saved.upload && saved.state==TransferState.UPLOADED && reference.length in 1..256)
        save(TransferEntry(saved.descriptor,saved.state,true,saved.createdAt,saved.references+reference))
    }
    /** No automatic caller. Future view-once must use a separate durable opening/presentation gate. */
    suspend fun download(descriptor:AttachmentDescriptor,reference:String,client:BlobTransferClient,
        allowed:()->Boolean):VerifiedAttachment=transfer {
        val epoch=generation.get(); checkpoint(epoch,allowed)
        val safe=AttachmentFormat.decode(AttachmentFormat.encode(descriptor))
        require(reference.length in 1..256)
        require(downloads.listFiles().orEmpty().filter { it.name!=safe.id }.sumOf { it.length() }+safe.ciphertextLength<=100L*1048576)
        require(directory.usableSpace>=safe.ciphertextLength+safe.paddedLength+1048576)
        val previous=entry(safe.id)
        if(previous!=null) require(previous.descriptor.digest.contentEquals(safe.digest) && previous.descriptor.key.contentEquals(safe.key))
        var saved=TransferEntry(safe,TransferState.DOWNLOADING,previous?.upload==true,previous?.createdAt ?: System.currentTimeMillis(),previous?.references.orEmpty()+reference)
        records.transaction { checkpoint(epoch,allowed); save(saved) }
        val ciphertext=file(downloads,safe.id)
        val plaintext=file(scratch,AttachmentFormat.newId())
        val context=currentCoroutineContext()
        try {
            val uploadCache=file(uploads,safe.id)
            if (!ciphertext.exists() && saved.upload && previous?.references?.isNotEmpty()==true && uploadCache.exists())
                Files.copy(uploadCache.toPath(),ciphertext.toPath())
            if (!ciphertext.exists() || ciphertext.length()!=safe.ciphertextLength ||
                !java.security.MessageDigest.isEqual(AttachmentFormat.digest(ciphertext),safe.digest)) {
                ciphertext.delete();client.download(safe,ciphertext)
            }
            checkpoint(epoch,allowed)
            saved=update(saved,TransferState.VERIFYING)
            AttachmentFormat.decrypt(safe,ciphertext,plaintext) { context.ensureActive(); checkpoint(epoch,allowed) }
            records.transaction { checkpoint(epoch,allowed); check(entry(safe.id)!=null); update(saved,TransferState.READY) }
            emit(AttachmentEvent.ATTACH_DOWNLOAD_SUCCESS)
            VerifiedAttachment(plaintext) { checkpoint(epoch,allowed); check(entry(safe.id)?.state==TransferState.READY) }
        } catch(e:Throwable) { plaintext.delete(); ciphertext.delete(); failed(saved); emit(AttachmentEvent.ATTACH_DOWNLOAD_FAILED); throw e }
    }
    private fun failed(saved:TransferEntry) = records.transaction {
        entry(saved.descriptor.id)?.let { update(it,TransferState.FAILED) }
    }
    /** Called after message deletion transaction; deliveryRequired protects hidden pending sends. */
    fun reconcileReferences(visible:Set<String>,deliveryRequired:Set<String>) {
        entries().forEach {
            val retained=it.references.filter { ref -> ref in visible || (it.upload && ref.substringAfterLast('/') in deliveryRequired) }.toSet()
            if(it.references.isNotEmpty() && retained.isEmpty()) remove(it.descriptor.id)
            else if(retained!=it.references) save(TransferEntry(it.descriptor,it.state,it.upload,it.createdAt,retained))
            else if(it.references.isEmpty() && System.currentTimeMillis()-it.createdAt>86400000L) remove(it.descriptor.id)
        }
    }
    fun remove(id:String) {
        invalidate()
        // Durable deletion marker first, physical cleanup retried on startup if interrupted.
        records.transaction { records.write("attachment/delete/$id",byteArrayOf(1)); records.remove(key(id)) }
        reconcile()
    }
    fun reconcile() {
        records.transaction { records.keys("attachment/delete/").forEach {
            val id=it.removePrefix("attachment/delete/")
            val files=listOf(file(uploads,id),file(downloads,id))
            if(files.all { target -> !target.exists() || target.delete() }) records.remove(it)
        } }
        val existing=entries().map { it.descriptor.id }.toSet()
        listOf(uploads,downloads).forEach { parent -> parent.listFiles()?.forEach {
            if(it.isFile && it.name !in existing) it.delete()
        } }
    }
}

/** Not a public pathname. Reading remains gated; close unlinks plaintext scratch. */
class VerifiedAttachment internal constructor(private val file:File,private val gate:()->Unit):AutoCloseable {
    override fun toString()="VerifiedAttachment(redacted)"
    fun copyTo(output:OutputStream) {
        gate()
        file.inputStream().use { input -> val buffer=ByteArray(8192)
            try { while(true) { gate(); val n=input.read(buffer); if(n<0) break; output.write(buffer,0,n) } }
            finally { buffer.fill(0) }
        }
    }
    override fun close() { file.delete() }
}
