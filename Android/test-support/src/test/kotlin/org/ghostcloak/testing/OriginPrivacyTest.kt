package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/** Runs the same header attack against memory and PostgreSQL through real Ktor HTTP. */
internal object OriginPrivacyProbe {
    val addresses=listOf("203.0.113.42","198.51.100.99","2001:db8::42")
    val headers=listOf("CF-Connecting-IP","CF-Connecting-IPv6","X-Forwarded-For","True-Client-IP","Forwarded","X-Real-IP")
    suspend fun exercise(db:BackendDatabase) {
        val logs=ByteArrayOutputStream();val stdout=System.out;val stderr=System.err
        val events=mutableListOf<Pair<ServerOperation,ServerResult>>()
        val principals=mutableListOf<String>()
        val service=MailboxService(db,rate=RateLimiter {_,p,_->principals.add(p);true},logger=ServerLogger {op,result->events.add(op to result)})
        val port=ServerSocket(0).use {it.localPort}
        val capture=PrintStream(logs,true,Charsets.UTF_8)
        System.setOut(capture);System.setErr(capture)
        try {
            ProductionHttpServer(service,{true},true,port).start().use {
                var sequence=0
                fun call(request:ApiRequest,token:String?=null):ApiResponse {
                    val c=URI("http://127.0.0.1:$port"+ApiRoutes.path(request)).toURL().openConnection() as HttpURLConnection
                    try {
                        c.requestMethod="POST";c.doOutput=true;c.connectTimeout=3000;c.readTimeout=10000
                        c.setRequestProperty("Content-Type",NetworkLimits.CONTENT_TYPE);c.setRequestProperty("X-Forwarded-Proto","https")
                        token?.let {c.setRequestProperty("Authorization","Bearer $it")}
                        val ip=addresses[sequence++%addresses.size]
                        headers.forEach {h->c.setRequestProperty(h,if(h=="Forwarded") "for=\"$ip\";proto=https" else ip)}
                        c.setRequestProperty("CF-IPCountry","ZZ");c.setRequestProperty("X-Unreviewed-Visitor",ip)
                        c.outputStream.use {out->out.write(NetworkCodec.encode(request))}
                        val status=c.responseCode
                        val bytes=(if(status>=400)c.errorStream else c.inputStream).use {input->input.readBytes()}
                        addresses.forEach {assertFalse(bytes.toString(Charsets.UTF_8).contains(it))}
                        val response=NetworkCodec.decode<ApiResponse>(bytes)
                        if(status>=400)throw ApiFailure(status,response.error ?: "rejected")
                        return response
                    } finally {c.disconnect()}
                }
                class Person(val name:String) {
                    val records=MemoryRecords();val engine=SignalProtocolEngine(records);val state=EndpointNetworkState(records,"ghostcloak.local")
                    lateinit var registration:Registration
                    suspend fun register() {
                        engine.createIdentity(name);registration=state.registration(name,listOf(engine.publicBundle().publicData()))
                        val r=registration
                        val c=call(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
                        call(ApiRequest.Register(r,c.id,state.sign(c)))
                        val login=call(ApiRequest.Issue(r.accountId,r.deviceId,"login")).challenge!!
                        state.save(call(ApiRequest.Verify(r.accountId,r.deviceId,login.id,state.sign(login))).session!!.token)
                    }
                }
                val a=Person("alice");val b=Person("bob");val c=Person("charlie")
                a.register();b.register();c.register()
                val entry=call(ApiRequest.Lookup("bob"),a.state.read()).directory!!
                assertEquals(b.registration.accountId,entry.accountId);assertEquals(b.registration.routingId,entry.routingId)
                a.engine.establishSession(entry.bundle.remote())
                val wire=EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId,"hello bob".toByteArray()))
                val send=ApiRequest.Send(RandomIdentifiers.create(),entry.routingId,wire)
                val id=call(send,a.state.read()).serverMessageId!!
                assertEquals(id,call(send,a.state.read()).serverMessageId)
                assertTrue(call(ApiRequest.Fetch(),c.state.read()).deliveries.isEmpty())
                try {call(ApiRequest.Ack(listOf(id)),c.state.read());fail()}catch(e:ApiFailure){assertEquals(403,e.status)}
                try {call(ApiRequest.Fetch());fail()}catch(e:ApiFailure){assertEquals(401,e.status)}
                val delivery=call(ApiRequest.Fetch(),b.state.read()).deliveries.single()
                assertEquals("hello bob",b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
                repeat(2){call(ApiRequest.Ack(listOf(id)),b.state.read())}
                assertTrue(call(ApiRequest.Fetch(),b.state.read()).deliveries.isEmpty())
                assertTrue(principals.all {it in setOf("anonymous",a.registration.deviceId,b.registration.deviceId,c.registration.deviceId)})
                val health=URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
                try {
                    headers.forEach {health.setRequestProperty(it,addresses.first())}
                    assertEquals(200,health.responseCode);assertEquals("{\"status\":\"ok\"}",health.inputStream.bufferedReader().use {r->r.readText()})
                } finally {health.disconnect()}
            }
        } finally {System.setOut(stdout);System.setErr(stderr);capture.close()}
        assertTrue(events.isNotEmpty())
        for(ip in addresses) {assertFalse(logs.toString(Charsets.UTF_8).contains(ip));assertFalse(events.toString().contains(ip));assertFalse(principals.contains(ip))}
    }
}

class OriginPrivacyTest {
    private val root=File(System.getProperty("ghostcloak.root"))
    @Test fun forwardedHeadersCannotChangeAuthenticationRoutingOrLogs()=runBlocking {
        val db=MemoryBackendDatabase();OriginPrivacyProbe.exercise(db)
        val dump=db.dump().toString(Charsets.ISO_8859_1)
        OriginPrivacyProbe.addresses.forEach {assertFalse(dump.contains(it))}
    }
    @Test fun backendHasNoVisitorAddressConsumersOrProxyTrustPlugins() {
        val sources=File(root,"backend/src/main/kotlin").walkTopDown().filter {it.extension=="kt"}.toList()
        val forbidden=Regex("remoteAddress|remoteHost|remoteAddr|request\\.origin|ForwardedHeaders|XForwardedHeaders|CF-Connecting-IP|X-Forwarded-For|True-Client-IP")
        assertTrue(sources.none {forbidden.containsMatchIn(it.readText())})
        val schema=File(root,"backend/src/main/resources/db/V001__foundation.sql").readText()
        assertFalse(Regex("\\b(inet|cidr|client_ip|visitor_ip|source_ip|remote_ip)\\b",RegexOption.IGNORE_CASE).containsMatchIn(schema))
    }
    @Test fun originBindsOnlyLocallyAndAllowsOnlyExplicitHeaders() {
        val config=File(root,"infrastructure/tunnel/nginx-origin.conf.template").readText().lineSequence().filterNot {it.trim().startsWith("#")}.joinToString("\n")
        val binds=Regex("(?m)^\\s*listen\\s+([^;]+);").findAll(config).map {it.groupValues[1]}.toList()
        assertEquals(listOf("unix:/run/ghostcloak-tunnel/origin.sock ssl"),binds)
        assertTrue(config.contains("proxy_pass_request_headers off;"))
        OriginPrivacyProbe.headers.forEach {assertTrue(config.contains("proxy_set_header $it \"\";"))}
        assertFalse(config.contains("remote_addr"));assertFalse(config.contains("real_ip_header"))
        assertTrue(config.contains("access_log off;"));assertTrue(config.contains("error_log /dev/null;"))
        val backend=File(root,"backend/src/main/kotlin/org/ghostcloak/backend/ProductionServer.kt").readText()
        assertTrue(backend.contains("connector {host=\"127.0.0.1\"; this.port=port}"))
        val tunnel=File(root,"infrastructure/tunnel/cloudflared.yml.template").readText()
        assertTrue(tunnel.contains("noTLSVerify: false"));assertTrue(tunnel.contains("originServerName: api.ghostcloak.org"))
        assertTrue(tunnel.contains("service: http_status:404"))
    }
}
