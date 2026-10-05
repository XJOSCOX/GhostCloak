package org.ghostcloak.messaging

import kotlinx.serialization.Serializable

/** Stored only in the endpoint's encrypted records. Photo bytes are never a server profile field. */
@Serializable data class LocalProfile(val revision:Long=1, val about:String="", val photo:ByteArray?=null,
    val sharing:Boolean=true) {
    override fun toString()="LocalProfile(redacted)"
}
@Serializable data class RemoteProfile(val revision:Long, val about:String="", val photo:ByteArray?=null) {
    override fun toString()="RemoteProfile(redacted)"
}
data class ProfileUpdate(val revision:Long, val displayName:String, val about:String,
    val photo:ByteArray?, val sharing:Boolean) {
    override fun toString()="ProfileUpdate(redacted)"
}

object ProfileRules {
    const val MAX_PHOTO_BYTES=8192
    const val MAX_ABOUT_BYTES=256
    const val MAX_PADDED_UPDATE=8704
    fun about(value:String):String {
        val result=value.trim()
        require(result.encodeToByteArray().size<=MAX_ABOUT_BYTES && result.encodeToByteArray().decodeToString()==result &&
            result.none {Character.isISOControl(it) || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'})
        return result
    }
    /** A structural JPEG gate. Android additionally decodes the small image before admission. */
    fun photo(bytes:ByteArray) {
        require(bytes.size in 128..MAX_PHOTO_BYTES && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() &&
            bytes[bytes.size-2]==0xff.toByte() && bytes.last()==0xd9.toByte())
        var at=2;var width=0;var height=0;var scan=false
        while(at+4<=bytes.size-2) {
            require(bytes[at++]==0xff.toByte())
            while(at<bytes.size && bytes[at]==0xff.toByte()) at++
            require(at+1<bytes.size-2)
            val marker=bytes[at++].toInt() and 255
            require(marker !in 0xe1..0xef && marker!=0xfe)
            val length=((bytes[at].toInt() and 255) shl 8) or (bytes[at+1].toInt() and 255)
            require(length>=2 && at+length<=bytes.size-2)
            if(marker in listOf(0xc0,0xc1,0xc2)) {
                require(length>=7)
                height=((bytes[at+3].toInt() and 255) shl 8) or (bytes[at+4].toInt() and 255)
                width=((bytes[at+5].toInt() and 255) shl 8) or (bytes[at+6].toInt() and 255)
            }
            if(marker==0xda) {scan=true;break}
            at+=length
        }
        require(scan && width in 1..256 && height==width)
    }
}
