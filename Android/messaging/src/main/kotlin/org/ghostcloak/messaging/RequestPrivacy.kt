package org.ghostcloak.messaging

import kotlinx.serialization.Serializable

@Serializable enum class RequestState { PENDING, ACCEPTED, REJECTED, BLOCKED, EXPIRED }
/** Derived membership; retained crypto/security rows are not address-book membership. */
enum class RelationshipState { UNKNOWN, REQUEST_PENDING, ACCEPTED_CONTACT, DORMANT_UNACCEPTED, BLOCKED }
@Serializable data class RequestRecord(val state:RequestState=RequestState.PENDING, val hidden:Boolean=true,
    val acceptedAt:Long?=null, val grace:ExpiryDeadline, val lastEnvelopeAt:Long?=null,
    val clockVersion:Int=0) {
    override fun toString()="RequestRecord(redacted)"
}
@Serializable data class ServerReference(val time:Long,val elapsed:Long,val boot:Int)
const val REQUEST_WINDOW=72L*60*60*1000
