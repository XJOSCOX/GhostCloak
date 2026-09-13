package org.ghostcloak.attachments

import kotlinx.coroutines.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.HttpGhostClient
import java.io.*
import java.net.HttpURLConnection
import java.net.URI

interface BlobTransferClient {
    suspend fun reserve(descriptor: AttachmentDescriptor): BlobResult
    suspend fun upload(descriptor: AttachmentDescriptor, ciphertext: File): BlobResult
    suspend fun download(descriptor: AttachmentDescriptor, ciphertext: File)
}

/** Uses the existing controller's client; never owns an identity, credential store or login path. */
class StreamingBlobClient(origin: String, private val auth: HttpGhostClient,
    private val eligible: () -> Boolean, private val checkpoint: () -> Unit,
    allowLoopbackForTests: Boolean = false) : BlobTransferClient {
    private val base=URI(origin)
    init {
        require(base.rawUserInfo==null && base.rawQuery==null && base.rawFragment==null && base.path in listOf("","/"))
        require(base.host!=null && (base.scheme=="https" || (allowLoopbackForTests && base.scheme=="http" && base.host in setOf("localhost","127.0.0.1"))))
    }
    private fun check() { if(!eligible()) throw CancellationException("attachment_unavailable"); checkpoint() }
    private suspend fun <T> exchange(method: String, path: String, body: File? = null, control: ByteArray? = null,
        capability: ByteArray? = null, response: (HttpURLConnection) -> T): T = withContext(Dispatchers.IO) {
        check()
        if (auth.fetchRetryDelayMillis>0) throw ApiFailure(429,"rate_limited",auth.fetchRetryDelayMillis)
        auth.authenticatedExchange(reportTransientFailure=false,beforeRenew=::check) { token ->
            check()
            val connection=base.resolve(path).toURL().openConnection() as HttpURLConnection
            val context=currentCoroutineContext()
            val deadline=System.nanoTime()+660_000_000_000L
            // Disconnect independently of blocking socket I/O on cancellation or total timeout.
            val watchdog=CoroutineScope(Dispatchers.IO).launch {
                try { while (context.isActive && System.nanoTime()<deadline) { delay(100); if (!eligible()) break } }
                catch (_:Exception) { /* Cancellation/state loss closes the socket without diagnostics. */ }
                finally { connection.disconnect() }
            }
            fun active() { context.ensureActive(); check(); if(System.nanoTime()>=deadline) throw IOException("attachment_timeout") }
            try {
                connection.instanceFollowRedirects=false; connection.requestMethod=method
                connection.connectTimeout=10000; connection.readTimeout=30000; connection.useCaches=false
                connection.setRequestProperty("Authorization","Bearer $token")
                connection.setRequestProperty("Accept-Encoding","identity")
                connection.setRequestProperty("Cache-Control","no-store")
                capability?.let { connection.setRequestProperty(BlobPolicy.CAPABILITY_HEADER,it.joinToString("") { b -> "%02x".format(b) }) }
                if(body!=null || control!=null) {
                    val length=body?.length() ?: control!!.size.toLong()
                    requireApi(length in 1..BlobPolicy.MAX_BYTES,"body_size",413)
                    connection.doOutput=true; connection.setFixedLengthStreamingMode(length)
                    connection.setRequestProperty("Content-Type",if(control!=null) NetworkLimits.CONTENT_TYPE else "application/octet-stream")
                    (body?.inputStream() ?: control!!.inputStream()).use { input -> connection.outputStream.use { output ->
                        val buffer=ByteArray(8192); var total=0L
                        while(true) { active(); val n=input.read(buffer); if(n<0) break
                            total+=n; requireApi(total<=length,"body_size",413); output.write(buffer,0,n) }
                        requireApi(total==length,"body_size",413)
                    } }
                }
                active(); val status=connection.responseCode
                if(status !in 200..299) throw ApiFailure(status,"attachment_rejected")
                requireApi(connection.contentEncoding in listOf(null,"identity"),"invalid_response",502)
                active(); response(connection).also { active() }
            } catch(e: IOException) {
                context.ensureActive()
                // HttpURLConnection may reject retrying a streamed body before exposing its 401 response.
                if(e is java.net.HttpRetryException && e.responseCode()==401) throw ApiFailure(401,"unauthorized")
                throw ApiFailure(503,"attachment_unavailable")
            } finally { watchdog.cancel(); connection.disconnect() }
        }
    }
    private fun result(connection: HttpURLConnection): BlobResult {
        requireApi(connection.contentType==NetworkLimits.CONTENT_TYPE,"invalid_response",502)
        val bytes=connection.inputStream.use { input ->
            val output=ByteArrayOutputStream(); val buffer=ByteArray(512)
            while(true) { val count=input.read(buffer); if(count<0) break
                requireApi(output.size()+count<=1024,"invalid_response",502); output.write(buffer,0,count) }
            output.toByteArray()
        }
        return NetworkCodec.decode<BlobResult>(bytes,1024).also { requireApi(it.version==1 && it.expiresAt>0,"invalid_response",502) }
    }
    override suspend fun reserve(descriptor: AttachmentDescriptor): BlobResult {
        descriptor.validate()
        return exchange("POST","/v1/attachments",control=NetworkCodec.encode(BlobReservation(id=descriptor.id,
            length=descriptor.ciphertextLength,digest=descriptor.digest,capabilityHash=DeviceAuth.digest(descriptor.capability))),response=::result)
    }
    override suspend fun upload(descriptor: AttachmentDescriptor, ciphertext: File): BlobResult {
        descriptor.validate(); require(ciphertext.length()==descriptor.ciphertextLength && java.security.MessageDigest.isEqual(AttachmentFormat.digest(ciphertext),descriptor.digest))
        return exchange("PUT","/v1/attachments/${descriptor.id}",body=ciphertext,response=::result).also { requireApi(it.complete,"invalid_response",502) }
    }
    override suspend fun download(descriptor: AttachmentDescriptor, ciphertext: File) {
        descriptor.validate()
        try {
            exchange("GET","/v1/attachments/${descriptor.id}",capability=descriptor.capability) { connection ->
                requireApi(connection.contentType=="application/octet-stream","invalid_response",502)
                requireApi(connection.contentLengthLong in listOf(-1L,descriptor.ciphertextLength),"invalid_response",502)
                connection.inputStream.use { input -> FileOutputStream(ciphertext).use { output ->
                    var total=0L; val buffer=ByteArray(8192)
                    while(true) { check(); val n=input.read(buffer); if(n<0) break
                        total+=n; requireApi(total<=descriptor.ciphertextLength,"response_size",502); output.write(buffer,0,n) }
                    requireApi(total==descriptor.ciphertextLength,"invalid_response",502); output.fd.sync()
                } }
            }
            require(java.security.MessageDigest.isEqual(AttachmentFormat.digest(ciphertext),descriptor.digest))
        } catch(e: Throwable) { ciphertext.delete(); throw e }
    }
}
