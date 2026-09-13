package org.ghostcloak.backend

import org.ghostcloak.protocol.*
import java.sql.*
import javax.sql.DataSource
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Cross-process transaction serialization preserves the existing check-then-write domain contract.
 * All repository users MUST acquire this advisory lock; throughput is deliberately bounded.
 * READ COMMITTED ensures reads after a waited lock see the previous owner's committed writes.
 */
class PostgresDatabase(private val source: DataSource, migrate: Boolean = false) : BackendDatabase {
    private val local = ThreadLocal<Connection>()
    private val permits = Semaphore(8)
    private val migration = javaClass.getResourceAsStream("/db/V001__foundation.sql")!!.use { it.readBytes() }
    private val checksum = DeviceAuth.digest(migration).joinToString("") { "%02x".format(it) }
    init {
        transaction {
            if (migrate) {
                execute("CREATE TABLE IF NOT EXISTS schema_history(version integer PRIMARY KEY, checksum char(64) NOT NULL)")
                if (query("SELECT version FROM schema_history") { it.getInt(1) }.isEmpty()) {
                    migration.toString(Charsets.UTF_8).split(';').filter { it.isNotBlank() }.forEach { execute(it) }
                    execute("INSERT INTO schema_history VALUES (1,?)", checksum)
                }
            }
            check(query("SELECT checksum FROM schema_history WHERE version=1") { it.getString(1) }.singleOrNull() == checksum) { "Migration validation failed" }
            val receiptMigration = javaClass.getResourceAsStream("/db/V002__delivery_receipts.sql")!!.use { it.readBytes() }
            val receiptChecksum = DeviceAuth.digest(receiptMigration).joinToString("") { "%02x".format(it) }
            if (migrate && query("SELECT version FROM schema_history WHERE version=2") { it.getInt(1) }.isEmpty()) {
                receiptMigration.toString(Charsets.UTF_8).split(';').filter { it.isNotBlank() }.forEach { execute(it) }
                execute("INSERT INTO schema_history VALUES (2,?)", receiptChecksum)
            }
            check(query("SELECT checksum FROM schema_history WHERE version=2") { it.getString(1) }.singleOrNull() == receiptChecksum) { "Migration validation failed" }
            val blobMigration = javaClass.getResourceAsStream("/db/V003__encrypted_blobs.sql")!!.use { it.readBytes() }
            val blobChecksum = DeviceAuth.digest(blobMigration).joinToString("") { "%02x".format(it) }
            if (migrate && query("SELECT version FROM schema_history WHERE version=3") { it.getInt(1) }.isEmpty()) {
                blobMigration.toString(Charsets.UTF_8).split(';').filter { it.isNotBlank() }.forEach { execute(it) }
                execute("INSERT INTO schema_history VALUES (3,?)", blobChecksum)
            }
            check(query("SELECT checksum FROM schema_history WHERE version=3") { it.getString(1) }.singleOrNull() == blobChecksum) { "Migration validation failed" }
            val recoveryMigration=javaClass.getResourceAsStream("/db/V004__device_binding_recovery.sql")!!.use {it.readBytes()}
            val recoveryChecksum=DeviceAuth.digest(recoveryMigration).joinToString("") {"%02x".format(it)}
            if(migrate && query("SELECT version FROM schema_history WHERE version=4") {it.getInt(1)}.isEmpty()) {
                recoveryMigration.toString(Charsets.UTF_8).split(';').filter {it.isNotBlank()}.forEach {execute(it)}
                execute("INSERT INTO schema_history VALUES (4,?)",recoveryChecksum)
            }
            check(query("SELECT checksum FROM schema_history WHERE version=4") {it.getString(1)}.singleOrNull()==recoveryChecksum) {"Migration validation failed"}
            check(query("SELECT count(*) FROM schema_history") { it.getInt(1) }.single() == 4) { "Unknown schema version" }
        }
    }
    override fun <T> transaction(block: () -> T): T {
        if (local.get() != null) return block()
        if (!permits.tryAcquire(5, TimeUnit.SECONDS)) throw ApiFailure(503,"database_unavailable")
        try {
            source.connection.use { c ->
                c.autoCommit=false; c.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED
                local.set(c)
                try {
                    execute("SET LOCAL statement_timeout='5s'"); execute("SET LOCAL lock_timeout='5s'")
                    execute("SELECT pg_advisory_xact_lock(734902182)")
                    val result=block(); c.commit(); return result
                } catch(e: Throwable) { c.rollback(); throw e }
                finally { local.remove() }
            }
        } catch(e: SQLException) { throw ApiFailure(503,"database_unavailable") }
        finally { permits.release() }
    }
    private fun statement(sql: String, args: Array<out Any?>): PreparedStatement = checkNotNull(local.get()).prepareStatement(sql).also { p ->
        args.forEachIndexed { i,v -> if(v is ByteArray) p.setBytes(i+1,v) else p.setObject(i+1,v) }
    }
    internal fun execute(sql:String,vararg args:Any?) { statement(sql,args).use { it.execute() } }
    internal fun <T> query(sql:String,vararg args:Any?, read:(ResultSet)->T):List<T> = statement(sql,args).use { p -> p.executeQuery().use { r -> buildList { while(r.next()) add(read(r)) } } }
    private fun <T> rows(table:String, idColumn:String="id", read:(ResultSet)->T, write:(String,T)->Unit) = object:Rows<T> {
        override fun get(id:String)=query("SELECT * FROM $table WHERE $idColumn=?",id,read=read).singleOrNull()
        override fun all()=query("SELECT * FROM $table",read=read)
        override fun size()=query("SELECT count(*) FROM $table") { it.getInt(1) }.single()
        override fun remove(id:String)=execute("DELETE FROM $table WHERE $idColumn=?",id)
        override fun put(id:String,row:T)=write(id,row)
    }
    override val accounts=rows("accounts",read={ AccountRow(it.getString("id"),it.getString("username"),it.getString("device_id")) }) { _,r ->
        execute("INSERT INTO accounts VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET username=excluded.username",r.id,r.username,r.deviceId)
    }
    override val blobs=rows("attachment_blobs",read={ BlobRow(it.getString("id"),it.getString("owner_account"),it.getString("owner_device"),it.getLong("encrypted_length"),it.getBytes("ciphertext_digest"),it.getBytes("capability_hash"),it.getLong("created_at"),it.getLong("expires_at"),it.getBoolean("complete"),it.getBoolean("uploading")) }) { _,r ->
        execute("INSERT INTO attachment_blobs VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET expires_at=excluded.expires_at,complete=excluded.complete,uploading=excluded.uploading",r.id,r.owner,r.device,r.length,r.digest,r.capabilityHash,r.created,r.expires,r.complete,r.uploading)
    }
    override val blobBudgets=rows("attachment_budgets",read={ BlobBudget(it.getString("id"),it.getLong("minute_bucket"),it.getInt("requests"),it.getLong("uploaded"),it.getLong("downloaded"),it.getLong("day_bucket")) }) { _,r ->
        execute("INSERT INTO attachment_budgets VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET minute_bucket=excluded.minute_bucket,requests=excluded.requests,uploaded=excluded.uploaded,downloaded=excluded.downloaded,day_bucket=excluded.day_bucket",r.id,r.minute,r.requests,r.upload,r.download,r.day)
    }
    override val devices=rows("devices",read={ DeviceRow(it.getString("id"),it.getString("account_id"),it.getString("routing_id"),it.getBytes("auth_public_key"),it.getBytes("identity_public_key")) }) { _,r ->
        execute("INSERT INTO devices VALUES (?,?,?,?,?)",r.id,r.accountId,r.routingId,r.authPublicKey,r.identity)
    }
    override val challenges=rows("auth_challenges",read={ ChallengeRow(Challenge(it.getString("id"),it.getBytes("random_bytes"),it.getLong("expires_at"),it.getString("audience"),it.getString("account_id"),it.getString("device_id"),it.getString("purpose"),it.getBytes("registration_hash"))) }) { _,r ->
        val c=r.challenge; execute("INSERT INTO auth_challenges VALUES (?,?,?,?,?,?,?,?)",c.id,c.accountId,c.deviceId,c.random,c.expiresAt,c.audience,c.purpose,c.registrationHash)
    }
    override val sessions=rows("access_sessions","token_hash",read={SessionRow(it.getString("token_hash"),it.getString("device_id"),it.getLong("expires_at"))}) { _,r -> execute("INSERT INTO access_sessions VALUES (?,?,?)",r.hash,r.deviceId,r.expiresAt) }
    override val mailbox=rows("mailbox_messages",read={MailboxRow(it.getString("id"),it.getString("recipient_routing_id"),it.getBytes("encrypted_envelope"),it.getLong("received_at"),it.getLong("expires_at"))}) { _,r -> execute("INSERT INTO mailbox_messages VALUES (?,?,?,?,?)",r.id,r.recipientRoutingId,r.encryptedEnvelope,r.receivedAt,r.expiresAt) }
    override val submissions=rows("message_deduplication",read={SubmissionRow(it.getString("id"),it.getString("sender_device_id"),it.getBytes("payload_hash"),it.getString("server_message_id"),it.getLong("expires_at"),it.getBoolean("acknowledged"))}) { _,r -> execute("INSERT INTO message_deduplication VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET acknowledged=excluded.acknowledged",r.id,r.sender,r.digest,r.serverId,r.expiresAt,r.acknowledged) }
    override val prekeys=object:Rows<PrekeyRow> {
        override fun get(id:String):PrekeyRow? {
            val device=devices.get(id) ?: return null
            val signed=query("SELECT key_id,public_key,signature FROM signed_prekeys WHERE device_id=?",id) { it.getInt(1) to (it.getBytes(2)+it.getBytes(3)) }.toMap()
            val ec=query("SELECT key_id FROM one_time_prekeys WHERE device_id=?",id) { it.getInt(1) }.toSet()
            val pq=query("SELECT key_id FROM pq_prekeys WHERE device_id=?",id) { it.getInt(1) }.toSet()
            val pool=query("""SELECT b.*,e.public_key AS ec_key,p.public_key AS pq_key,p.signature AS pq_signature FROM prekey_bundles b
                JOIN one_time_prekeys e ON e.device_id=b.device_id AND e.key_id=b.ec_id
                JOIN pq_prekeys p ON p.device_id=b.device_id AND p.key_id=b.pq_id WHERE b.device_id=? ORDER BY position""",id) {
                val s=signed.getValue(it.getInt("signed_id"))
                PublicBundle(id,it.getInt("registration_id"),device.identity,it.getInt("ec_id"),it.getBytes("ec_key"),it.getInt("signed_id"),s.copyOfRange(0,33),s.copyOfRange(33,97),it.getInt("pq_id"),it.getBytes("pq_key"),it.getBytes("pq_signature"))
            }
            return PrekeyRow(id,pool,ec,pq,signed)
        }
        override fun put(id:String,row:PrekeyRow) {
            execute("DELETE FROM prekey_bundles WHERE device_id=?",id)
            row.signed.forEach { (key,value) -> execute("INSERT INTO signed_prekeys VALUES (?,?,?,?) ON CONFLICT DO NOTHING",id,key,value.copyOfRange(0,33),value.copyOfRange(33,97)) }
            row.usedEc.forEach { execute("INSERT INTO one_time_prekeys(device_id,key_id) VALUES (?,?) ON CONFLICT DO NOTHING",id,it) }
            row.usedPq.forEach { execute("INSERT INTO pq_prekeys(device_id,key_id) VALUES (?,?) ON CONFLICT DO NOTHING",id,it) }
            execute("UPDATE one_time_prekeys SET public_key=NULL WHERE device_id=?",id)
            execute("UPDATE pq_prekeys SET public_key=NULL,signature=NULL WHERE device_id=?",id)
            row.pool.forEachIndexed { index,b ->
                execute("UPDATE one_time_prekeys SET public_key=? WHERE device_id=? AND key_id=?",b.preKey,id,b.preKeyId)
                execute("UPDATE pq_prekeys SET public_key=?,signature=? WHERE device_id=? AND key_id=?",b.kyberKey,b.kyberSignature,id,b.kyberId)
                execute("INSERT INTO prekey_bundles VALUES (?,?,?,?,?,?)",id,b.preKeyId,b.kyberId,b.signedId,b.registrationId,index)
            }
        }
        override fun all()=devices.all().mapNotNull { get(it.id) }
        override fun size()=devices.size()
        override fun remove(id:String) { error("Prekey identity history cannot be erased") }
    }
    fun healthy():Boolean = try { transaction { query("SELECT 1") { it.getInt(1) }.single()==1 } } catch(_:Exception) { false }
    fun checkServiceRole()=transaction {
        check(query("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname=current_user") { it.getBoolean(1) }.single()) { "Restricted database role required" }
    }
    fun cleanupRateLimits(now:Long)=transaction {
        execute("DELETE FROM rate_limits WHERE window_start < ?",now/60000)
        // Retain issued IDs/signed-key history to reject reuse; discard consumed EC/PQ bytes.
        execute("UPDATE one_time_prekeys e SET public_key=NULL WHERE public_key IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prekey_bundles b WHERE b.device_id=e.device_id AND b.ec_id=e.key_id)")
        execute("UPDATE pq_prekeys p SET public_key=NULL,signature=NULL WHERE public_key IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prekey_bundles b WHERE b.device_id=p.device_id AND b.pq_id=p.key_id)")
    }
}
class PostgresRateLimiter(private val db:PostgresDatabase, private val limit:Int=60):RateLimiter {
    override fun allow(operation:ServerOperation,principal:String,now:Long):Boolean=db.transaction {
        val bucket=DeviceAuth.digest(principal.toByteArray()).joinToString("") {"%02x".format(it)}
        db.execute("DELETE FROM rate_limits WHERE window_start < ?",now/60000)
        val row=db.query("SELECT count FROM rate_limits WHERE operation=? AND principal=?",operation.name,bucket) {it.getInt(1)}.singleOrNull() ?: 0
        if(row==0 && db.query("SELECT count(*) FROM rate_limits") {it.getInt(1)}.single()>=2048) return@transaction false
        val maximum=if(operation in setOf(ServerOperation.RECOVER_ISSUE,ServerOperation.RECOVER_VERIFY)) minOf(limit,10) else limit
        if(row>=maximum) false else {
            db.execute("INSERT INTO rate_limits VALUES (?,?,?,1) ON CONFLICT(operation,principal) DO UPDATE SET count=rate_limits.count+1",operation.name,bucket,now/60000); true
        }
    }
}
