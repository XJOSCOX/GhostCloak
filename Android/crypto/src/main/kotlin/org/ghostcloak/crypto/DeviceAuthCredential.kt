package org.ghostcloak.crypto

/** Separate from Signal identity. Platform implementations keep private material non-exportable. */
interface DeviceAuthCredential {
    fun publicKey(alias:String, create:Boolean):ByteArray
    fun sign(alias:String,statement:ByteArray):ByteArray
}
