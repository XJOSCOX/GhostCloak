package org.ghostcloak.messaging

import org.ghostcloak.identity.RandomIdentifiers
import java.security.MessageDigest
import java.util.PriorityQueue

/** The sequence scope survives a Signal ratchet/session rebuild, but changes if
 * either device ID or pinned identity key changes. IDs and public-key digests
 * are domain separated and directionally ordered to prevent scope collisions. */
internal object DirectOrderingV1 {
    const val MAX_SEQUENCE=1_000_000_000_000L
    fun scope(senderDeviceId:String,recipientDeviceId:String,
        senderIdentityDigest:ByteArray,recipientIdentityDigest:ByteArray):ByteArray {
        require(RandomIdentifiers.valid(senderDeviceId) &&
            RandomIdentifiers.valid(recipientDeviceId) && senderDeviceId!=recipientDeviceId &&
            senderIdentityDigest.size==32 && recipientIdentityDigest.size==32)
        return MessageDigest.getInstance("SHA-256").apply {
            update("GhostCloak/direct-order/v1".encodeToByteArray())
            update(senderDeviceId.encodeToByteArray())
            update(recipientDeviceId.encodeToByteArray())
            update(senderIdentityDigest)
            update(recipientIdentityDigest)
        }.digest()
    }
    fun valid(sequence:Long,scope:ByteArray)=sequence in 1..MAX_SEQUENCE && scope.size==32
    fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it)}
}

/** Type-23 is an encrypted wrapper around one canonical legacy direct user
 * frame. It is never a control or group frame and is sent only after an
 * authenticated direct-ordering capability advertisement. */
class DirectOrderedContent(val senderSequence:Long,val scopeDigest:ByteArray,
    val inner:ConversationPayload.Content)

/** Stable k-way merge of locally accepted rows. Ordered rows must follow their
 * sender's sequence; unrelated streams retain local timestamp preference. The
 * timestamp never overrides a same-sender sequence constraint. */
internal fun mergeDirectPresentation(rows:List<Message>):List<Message> {
    if(rows.size<2) return rows
    val streams=rows.groupBy {row ->
        val scope=row.orderingScopeDigest
        if(scope==null || row.senderSequence==null) "legacy:${row.localId}"
        else "ordered:${row.direction}:${DirectOrderingV1.hex(scope)}"
    }.values.map {stream ->
        if(stream.first().senderSequence==null) stream else stream.sortedWith(
            compareBy<Message> {it.senderSequence}.thenBy {it.localId})
    }
    data class Cursor(val stream:Int,val offset:Int)
    val legacyPosition=rows.mapIndexed {index,row ->row.localId to index}.toMap()
    val queue=PriorityQueue<Cursor>(compareBy<Cursor> {streams[it.stream][it.offset].timestamp}
        .thenBy {legacyPosition[streams[it.stream][it.offset].localId] ?: Int.MAX_VALUE})
    streams.indices.forEach {queue.add(Cursor(it,0))}
    return buildList(rows.size) {
        while(queue.isNotEmpty()) {
            val cursor=queue.remove()
            add(streams[cursor.stream][cursor.offset])
            if(cursor.offset+1<streams[cursor.stream].size)
                queue.add(Cursor(cursor.stream,cursor.offset+1))
        }
    }
}
