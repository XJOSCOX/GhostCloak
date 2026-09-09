package org.ghostcloak.testing

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.*
import java.util.concurrent.Executors

/** Isolated loopback test ingress. Trust is scoped to this test JVM and restored on close. */
internal class TestTlsIngress(port:Int):AutoCloseable {
    @Volatile var failNextSubmission:String?=null
    private val directory=Files.createTempDirectory("ghostcloak-tls-test")
    private val file=directory.resolve("test.p12")
    private val original=HttpsURLConnection.getDefaultSSLSocketFactory()
    private val executor=Executors.newFixedThreadPool(4)
    private val server:HttpsServer
    val origin:String
    init {
        val password=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
        val command=java.nio.file.Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").toString()
        val process=ProcessBuilder(command,"-genkeypair","-alias","test","-keyalg","EC","-groupname","secp256r1","-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass:env","GC_TEST_CERT_PASSWORD").apply {environment()["GC_TEST_CERT_PASSWORD"]=password; redirectOutput(ProcessBuilder.Redirect.DISCARD); redirectError(ProcessBuilder.Redirect.DISCARD)}.start()
        check(process.waitFor()==0)
        val keys=KeyStore.getInstance("PKCS12").apply {Files.newInputStream(file).use {load(it,password.toCharArray())}}
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(keys,password.toCharArray())}
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("test",keys.getCertificate("test"))}
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(trust)}
        val ssl=SSLContext.getInstance("TLS").apply {init(km.keyManagers,tm.trustManagers,SecureRandom())}
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        server=HttpsServer.create(InetSocketAddress("127.0.0.1",0),16).apply {
            httpsConfigurator=HttpsConfigurator(ssl); this.executor=this@TestTlsIngress.executor
            createContext("/") {exchange->
                val fault=if(exchange.requestURI.rawPath=="/v1/messages") failNextSubmission.also {failNextSubmission=null} else null
                if(fault=="before") {exchange.sendResponseHeaders(504,-1);exchange.close();return@createContext}
                val connection=URI("http://127.0.0.1:$port"+exchange.requestURI.rawPath).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod=exchange.requestMethod; connection.connectTimeout=3000; connection.readTimeout=15000
                    connection.setRequestProperty("X-Forwarded-Proto","https")
                    for(h in listOf("Content-Type","Authorization")) exchange.requestHeaders.getFirst(h)?.let {connection.setRequestProperty(h,it)}
                    if(exchange.requestMethod=="POST") {val bytes=exchange.requestBody.readNBytes(262145); check(bytes.size<=262144);connection.doOutput=true;connection.outputStream.use {it.write(bytes)}}
                    val status=connection.responseCode
                    if(fault=="after") {exchange.sendResponseHeaders(504,-1);exchange.close();return@createContext}
                    val body=(if(status>=400) connection.errorStream else connection.inputStream)?.use {it.readBytes()} ?: byteArrayOf()
                    connection.contentType?.let {exchange.responseHeaders.set("Content-Type",it)}
                    exchange.sendResponseHeaders(status,body.size.toLong()); exchange.responseBody.use {it.write(body)}
                } finally {connection.disconnect();exchange.close()}
            }
            start()
        }
        origin="https://localhost:${server.address.port}"
    }
    override fun close(){server.stop(0);executor.shutdownNow();HttpsURLConnection.setDefaultSSLSocketFactory(original);Files.deleteIfExists(file);Files.deleteIfExists(directory)}
}
