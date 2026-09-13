package org.ghostcloak.backend

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import org.ghostcloak.protocol.*

fun Route.blobRoutes(service: BlobService, production: Boolean) {
    route("/v1/attachments") {
        suspend fun checked(call: ApplicationCall, action: suspend (String) -> Unit) {
            try {
                requireApi(call.request.queryParameters.isEmpty(),"invalid_request")
                requireApi(!production || call.request.headers["X-Forwarded-Proto"] == "https","tls_ingress_required",403)
                requireApi(call.request.headers[HttpHeaders.ContentEncoding] == null,"content_encoding",415)
                val values = call.request.headers.getAll(HttpHeaders.Authorization)
                requireApi(values?.size == 1 && values.single().startsWith("Bearer "),"unauthorized",401)
                call.response.headers.append(HttpHeaders.CacheControl,"no-store")
                call.response.headers.append("X-Content-Type-Options","nosniff")
                withTimeout(660_000) { action(values!!.single().removePrefix("Bearer ")) }
            } catch (e: Exception) {
                val status = when(e) { is ApiFailure -> e.status; is TimeoutCancellationException -> 408; else -> 503 }
                call.respondBytes(byteArrayOf(),ContentType.Application.OctetStream,HttpStatusCode.fromValue(status))
            }
        }
        post {
            checked(call) { token ->
                requireApi(call.request.headers[HttpHeaders.ContentType] == NetworkLimits.CONTENT_TYPE,"content_type",415)
                val channel = call.receiveChannel(); val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while(true) { val n=channel.readAvailable(buffer); if(n<0) break
                    requireApi(bytes.size()+n<=1024,"body_size",413); bytes.write(buffer,0,n) }
                val result = withContext(Dispatchers.IO) { service.reserve(token,NetworkCodec.decode<BlobReservation>(bytes.toByteArray(),1024)) }
                call.respondBytes(NetworkCodec.encode(result),ContentType.parse(NetworkLimits.CONTENT_TYPE))
            }
        }
        put("/{id}") {
            checked(call) { token ->
                requireApi(call.request.headers[HttpHeaders.ContentType] == "application/octet-stream","content_type",415)
                val length=call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                requireApi(length==null || length in 1..BlobPolicy.MAX_BYTES,"body_size",413)
                val id=call.parameters["id"] ?: ""; requireApi(BlobPolicy.validId(id),"unavailable",404)
                val context = currentCoroutineContext()
                val result=withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use {
                    service.upload(token,id,it) { context.ensureActive() }
                } }
                call.respondBytes(NetworkCodec.encode(result),ContentType.parse(NetworkLimits.CONTENT_TYPE))
            }
        }
        get("/{id}") {
            checked(call) { token ->
                val id=call.parameters["id"] ?: ""; requireApi(BlobPolicy.validId(id),"unavailable",404)
                val capabilities=call.request.headers.getAll(BlobPolicy.CAPABILITY_HEADER)
                val input=withContext(Dispatchers.IO) { service.download(token,id,capabilities?.singleOrNull()) }
                // No path/filename handed to a static-file responder.
                try { call.respondOutputStream(ContentType.Application.OctetStream) {
                    input.use { source -> val buffer=ByteArray(8192)
                        while(true) { currentCoroutineContext().ensureActive(); val n=source.read(buffer); if(n<0) break; write(buffer,0,n) }
                    }
                } } finally { input.close() }
            }
        }
    }
}
