package org.ghostcloak.storage

/** Application-owned fence. The callback and the transition share a lock, including DB opening.
 * Closing an existing handle is deliberately outside this gate. No deletion is authorized here. */
interface LocalStateAccess {
    fun <T> access(block: () -> T): T
}

interface LocalStateAccessOwner {
    val localStateAccess: LocalStateAccess
}
