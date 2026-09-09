package org.ghostcloak.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.ghostcloak.crypto.DeviceAuthCredential
import org.ghostcloak.crypto.EndpointStorageFailure
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** No software fallback, PKCS#8 export or automatic replacement of a missing registered key. */
class KeystoreDeviceAuth:DeviceAuthCredential {
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    @Synchronized override fun publicKey(alias:String,create:Boolean):ByteArray = guarded {
        val keys=store()
        if(!keys.containsAlias(alias)) {
            if(!create) throw EndpointStorageFailure()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        check(store().getKey(alias,null).encoded==null) {"Non-exportable key required"}
        store().getCertificate(alias).publicKey.encoded
    }
    override fun sign(alias:String,statement:ByteArray):ByteArray=guarded {
        val key=store().getKey(alias,null) as? PrivateKey ?: throw EndpointStorageFailure()
        check(key.encoded==null)
        Signature.getInstance("SHA256withECDSA").run {initSign(key); update(statement); sign()}
    }
    private fun <T> guarded(block:()->T):T=try {block()} catch(_:Exception) {throw EndpointStorageFailure()}
}
