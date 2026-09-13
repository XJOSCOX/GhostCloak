package org.ghostcloak.backend

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.ghostcloak.protocol.*
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProductionConfig(val mode:String,val databaseUrl:String,val databaseUser:String,val databasePassword:String,val origin:String,val port:Int=8787) {
    val audience:String
    init {
        require(mode in setOf("development","test","production")) {"Invalid mode"}
        val uri=URI(origin)
        require(uri.scheme=="https" && uri.host!=null && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path in setOf("","/")) {"HTTPS origin required"}
        audience=uri.host
        require(audience.matches(Regex("[a-z0-9][a-z0-9.-]{0,99}"))) {"DNS origin required"}
        require(databaseUrl.matches(Regex("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/[a-zA-Z0-9_]+"))) {"Private database URL required"}
        require(databaseUser.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]{0,62}")) && databasePassword.length in 20..256) {"Database credentials required"}
        require(port in 1024..65535)
        if(mode=="production") require(databaseUser !in setOf("postgres","test") && !origin.contains("example.") && !origin.contains("localhost") && !audience.matches(Regex("[0-9.]+"))) {"Production configuration required"}
    }
    fun dataSource()=PGSimpleDataSource().apply { setURL(databaseUrl); user=databaseUser; password=databasePassword; connectTimeout=5; socketTimeout=10; loginTimeout=5 }
    companion object {
        fun environment(env:Map<String,String> = System.getenv())=ProductionConfig(
            env["GHOSTCLOAK_MODE"] ?: error("Mode required"), env["GHOSTCLOAK_DATABASE_URL"] ?: error("Database required"),
            env["GHOSTCLOAK_DATABASE_USER"] ?: error("Database user required"), env["GHOSTCLOAK_DATABASE_PASSWORD"] ?: error("Database secret required"),
            env["GHOSTCLOAK_PUBLIC_ORIGIN"] ?: error("Origin required"))
    }
}
class ProductionHttpServer(private val service:MailboxService, private val healthy:()->Boolean,
    private val production:Boolean=true, port:Int=8787, private val blobs: BlobService? = null) : AutoCloseable {
    private val permits=Semaphore(32)
    private val server=embeddedServer(Netty,serverConfig { module {
        routing {
            blobs?.let { blobRoutes(it, production) }
            get("/health") {
                val ok=withContext(Dispatchers.IO) { healthy() }
                call.response.headers.append("Cache-Control","no-store")
                call.respondText(if(ok) "{\"status\":\"ok\"}" else "{\"status\":\"unavailable\"}",ContentType.Application.Json,if(ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
            }
            route("/{path...}") { handle {
                var acquired=false
                try {
                    acquired=permits.tryAcquire(); requireApi(acquired,"busy",429)
                    val response=withTimeout(10000) {
                        requireApi(call.request.httpMethod==HttpMethod.Post,"method_not_allowed",405)
                        requireApi(call.request.queryParameters.isEmpty(),"invalid_path")
                        requireApi(!production || call.request.headers["X-Forwarded-Proto"]=="https","tls_ingress_required",403)
                        requireApi(call.request.headers[HttpHeaders.ContentType]==NetworkLimits.CONTENT_TYPE,"content_type",415)
                        requireApi((call.request.headers.getAll(HttpHeaders.Authorization)?.size ?: 0)<=1)
                        val length=call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        requireApi(length==null || length in 1..NetworkLimits.BODY.toLong(),"body_size",413)
                        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); val input=call.receiveChannel()
                        while(true) {val n=input.readAvailable(buffer); if(n<0) break; requireApi(out.size()+n<=NetworkLimits.BODY,"body_size",413); out.write(buffer,0,n)}
                        val request=NetworkCodec.decode<ApiRequest>(out.toByteArray())
                        requireApi(call.request.path()==ApiRoutes.path(request),"invalid_path",404)
                        val auth=call.request.headers[HttpHeaders.Authorization]
                        requireApi(auth==null || auth.startsWith("Bearer "),"unauthorized",401)
                        withContext(Dispatchers.IO) {service.execute(request,auth?.removePrefix("Bearer "))}
                    }
                    call.response.headers.append("Cache-Control","no-store")
                    call.response.headers.append("X-Content-Type-Options","nosniff")
                    call.respondBytes(NetworkCodec.encode(response),ContentType.parse(NetworkLimits.CONTENT_TYPE))
                } catch(e:Exception) {
                    val error=when(e) {is ApiFailure->e; is TimeoutCancellationException->ApiFailure(408,"request_timeout"); else->ApiFailure(503,"unavailable")}
                    call.response.headers.append("Cache-Control","no-store")
                    call.respondBytes(NetworkCodec.encode(ApiResponse(error=error.code)),ContentType.parse(NetworkLimits.CONTENT_TYPE),HttpStatusCode.fromValue(error.status))
                } finally {if(acquired) permits.release()}
            } }
        }
    } }) {
        connector {host="127.0.0.1"; this.port=port}
        connectionGroupSize=2; workerGroupSize=4; callGroupSize=8
        runningLimit=64; maxHeaderSize=8192; maxInitialLineLength=2048
        requestReadTimeoutSeconds=660; responseWriteTimeoutSeconds=660; enableHttp2=false
    }
    fun start():ProductionHttpServer {server.start(wait=false); return this}
    override fun close(){server.stop(1000,3000)}
}
class RetentionWorker(private val service:MailboxService,private val db:PostgresDatabase,private val blobs:BlobService? = null):AutoCloseable {
    private val executor=Executors.newSingleThreadScheduledExecutor()
    @Volatile var healthy=true; private set
    init {executor.scheduleWithFixedDelay({try {service.cleanup(); db.cleanupRateLimits(System.currentTimeMillis()); blobs?.cleanup(); healthy=true} catch(_:Exception) {healthy=false}},0,30,TimeUnit.SECONDS)}
    override fun close(){executor.shutdownNow()}
}
fun main(args:Array<String>) {
    java.util.logging.Logger.getLogger("org.postgresql").level=java.util.logging.Level.OFF
    // Do not let a JDBC/configuration exception print credentials, SQL values or stack traces.
    try {
        val config=ProductionConfig.environment()
        val db=PostgresDatabase(config.dataSource(),migrate=args.contentEquals(arrayOf("migrate")))
        if(args.contentEquals(arrayOf("migrate"))) return
        require(args.isEmpty())
        db.checkServiceRole()
        val service=MailboxService(db,policy=BackendPolicy(audience=config.audience),rate=PostgresRateLimiter(db))
        // Explicit deployment opt-in after directory/quota/ingress checks. Absent means no blob API.
        val blobs=System.getenv("GHOSTCLOAK_ATTACHMENTS_DIR")?.let { BlobService(db,service,java.io.File(it)) }
        val worker=RetentionWorker(service,db,blobs)
        val server=ProductionHttpServer(service,{db.healthy() && worker.healthy},config.mode=="production",config.port,blobs).start()
        Runtime.getRuntime().addShutdownHook(Thread {server.close(); worker.close(); blobs?.close()})
        java.util.concurrent.CountDownLatch(1).await()
    } catch(_:Exception) {kotlin.system.exitProcess(1)}
}
