package org.ghostcloak.messaging

import org.ghostcloak.protocol.DeviceAuth

/** Invitee-only terminal-ID filter. At the 512-entry cap false positives stay rare. */
internal object GroupSenderDeleteFilterV1 {
    const val BYTES=1536
    const val MAX_ENTRIES=512
    private const val HASHES=12
    fun empty()=ByteArray(BYTES)
    fun add(bits:ByteArray,groupId:String,logicalId:String) {
        require(bits.size==BYTES && GroupIds.valid(groupId) && GroupIds.valid(logicalId))
        val seed=DeviceAuth.digest("GhostCloak.GroupSenderDeleteFilter.v1".encodeToByteArray()+
            groupId.encodeToByteArray()+logicalId.encodeToByteArray())
        for(i in 0 until HASHES) {
            val bit=(((seed[i*2].toInt() and 255) shl 8) or
                (seed[i*2+1].toInt() and 255)) % (BYTES*8)
            bits[bit/8]=(bits[bit/8].toInt() or (1 shl (bit%8))).toByte()
        }
    }
    fun contains(bits:ByteArray,groupId:String,logicalId:String):Boolean {
        require(bits.size==BYTES)
        val probe=empty();add(probe,groupId,logicalId)
        return probe.indices.all {i -> (bits[i].toInt() and probe[i].toInt())==probe[i].toInt() }
    }
}
