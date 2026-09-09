package org.ghostcloak.backend

import com.sun.net.httpserver.HttpServer
import org.ghostcloak.protocol.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Explicit loopback-only development adapter. No TLS, proxy trust, CORS or deployment mode. */
class LocalServer(val service: MailboxService = MailboxService(MemoryBackendDatabase()), port: Int = 0) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32)
    private val executor = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32))
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                requireApi(exchange.requestMethod == "POST", "method_not_allowed", 405)
                requireApi(exchange.requestURI.rawQuery == null, "invalid_path")
                requireApi(exchange.requestHeaders.getFirst("Content-Type") == NetworkLimits.CONTENT_TYPE, "content_type", 415)
                requireApi(exchange.requestHeaders["Authorization"]?.size ?: 0 <= 1)
                val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
                requireApi(length == null || length in 1..NetworkLimits.BODY.toLong(), "body_size", 413)
                val bytes = exchange.requestBody.use { it.readNBytes(NetworkLimits.BODY + 1) }
                val request = NetworkCodec.decode<ApiRequest>(bytes)
                requireApi(exchange.requestURI.rawPath == ApiRoutes.path(request), "invalid_path", 404)
                val header = exchange.requestHeaders.getFirst("Authorization")
                requireApi(header == null || header.startsWith("Bearer "), "unauthorized", 401)
                val response = service.execute(request, header?.removePrefix("Bearer "))
                val body = NetworkCodec.encode(response)
                exchange.responseHeaders.set("Content-Type", NetworkLimits.CONTENT_TYPE)
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (e: Exception) {
                // Never include raw exceptions, request bodies, tokens or addresses in responses/logs.
                val failure = e as? ApiFailure
                val body = NetworkCodec.encode(ApiResponse(error = failure?.code ?: "internal_error"))
                try {
                    exchange.responseHeaders.set("Content-Type", NetworkLimits.CONTENT_TYPE)
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(failure?.status ?: 500, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (_: Exception) { /* Client disconnected; no payload logging. */ }
            } finally { exchange.close() }
        }
    }
    fun start(): LocalServer { server.start(); return this }
    override fun close() { server.stop(0); executor.shutdownNow() }
}
fun main() {
    val server = LocalServer(port = 8787).start()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    java.util.concurrent.CountDownLatch(1).await()
}
