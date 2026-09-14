package org.ghostcloak.messaging

import kotlinx.serialization.Serializable

@Serializable enum class RequestState { PENDING, ACCEPTED, REJECTED, BLOCKED, EXPIRED }
@Serializable data class RequestRecord(val state:RequestState=RequestState.PENDING, val hidden:Boolean=true,
    val acceptedAt:Long?=null, val grace:ExpiryDeadline, val lastEnvelopeAt:Long?=null) {
    override fun toString()="RequestRecord(redacted)"
}
@Serializable data class ServerReference(val time:Long,val elapsed:Long,val boot:Int)
const val REQUEST_WINDOW=72L*60*60*1000
