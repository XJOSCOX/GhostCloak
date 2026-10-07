package org.ghostcloak.messaging

import org.ghostcloak.protocol.DeviceAuth

/** Signed, bounded join-only set commitment. False positives fail closed. */
internal object GroupModerationFilterV1 {
    const val BYTES=2048
    private const val HASHES=12
    fun empty()=ByteArray(BYTES)
    fun add(bits:ByteArray,groupId:String,logicalId:String) {
        require(bits.size==BYTES && GroupIds.valid(groupId) && GroupIds.valid(logicalId))
        val seed=DeviceAuth.digest("GhostCloak.GroupModerationFilter.v1".encodeToByteArray()+
            groupId.encodeToByteArray()+logicalId.encodeToByteArray())
        for(i in 0 until HASHES) {
            val first=(seed[i*2].toInt() and 255)
            val second=(seed[i*2+1].toInt() and 255)
            val bit=((first shl 8) or second) % (BYTES*8)
            bits[bit/8]=(bits[bit/8].toInt() or (1 shl (bit%8))).toByte()
        }
    }
    fun contains(bits:ByteArray,groupId:String,logicalId:String):Boolean {
        require(bits.size==BYTES)
        val probe=empty();add(probe,groupId,logicalId)
        return probe.indices.all {i -> (bits[i].toInt() and probe[i].toInt())==probe[i].toInt() }
    }
}
