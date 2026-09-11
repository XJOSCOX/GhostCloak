package org.ghostcloak.app

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundSyncTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun lifecycleAutomaticallyReceivesRequestsRepliesAndDeliveryAndStopsPolling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as Application
        val api = SyntheticNetwork()
        val a = AppRuntime(context, "https://fixture.invalid", "a-${RandomIdentifiers.create()}", api)
        val b = AppRuntime(context, "https://fixture.invalid", "b-${RandomIdentifiers.create()}", api)
        val stores = listOf(ViewModelStore(), ViewModelStore())
        var loops = emptyList<Job>()
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }
            a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            val models = withContext(Dispatchers.Main) {
                listOf(GhostViewModel(app, a), GhostViewModel(app, b)).also { models ->
                    models.forEachIndexed { i, model -> stores[i].put("test", model) }
                }
            }
            val owners = withContext(Dispatchers.Main) { listOf(Owner(), Owner()) }
            loops = owners.mapIndexed { i, owner -> launch(Dispatchers.Main) {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { models[i].foregroundSync() }
            } }
            a.use { a.send(it, bid, "Synthetic one-way A1") }
            assertTrue(b.use { it.contacts().isEmpty() })
            withContext(Dispatchers.Main) { owners[0].registry.currentState = Lifecycle.State.STARTED }
            delay(500)
            assertEquals(MessageState.SERVER_ACCEPTED, a.use { it.messages(bid).single().state })
            val firstCycle = api.requests
            // Only Alice is STARTED. A subsequent poll must occur at the new cadence.
            withTimeout(2000) { while (api.requests == firstCycle) delay(25) }
            withContext(Dispatchers.Main) { owners[1].registry.currentState = Lifecycle.State.STARTED }
            withTimeout(15000) {
                while (models[1].state.value.contacts.isEmpty() || a.use { it.messages(bid).single().state } != MessageState.DELIVERED) delay(100)
            }
            assertTrue(models[1].state.value.contacts.single().contact.request)
            assertEquals(IdentityTrustState.UNVERIFIED, models[1].state.value.contacts.single().identity!!.trustState)
            withContext(Dispatchers.Main) { models[1].select(aid) }
            b.use { it.acceptRequest(aid); b.send(it, aid, "Synthetic reply B1") }
            withTimeout(15000) { while (a.use { it.messages(bid).size } != 2) delay(100) }
            withContext(Dispatchers.Main) { owners.forEach { it.registry.currentState = Lifecycle.State.CREATED } }
            delay(300) // Allow cancellation to release the serialized runtime operation.
            val stopped = api.requests
            delay(2500) // More than two polling intervals: STOPPED must make no requests.
            assertEquals(stopped, api.requests)
            a.use { a.send(it, bid, "After resume") }
            withContext(Dispatchers.Main) {
                owners[1].registry.currentState = Lifecycle.State.STARTED
                owners[1].registry.currentState = Lifecycle.State.RESUMED
            }
            withTimeout(3000) { while (b.use { it.messages(aid).size } != 3) delay(50) }
            assertEquals(3, b.use { it.messages(aid).size })
            assertEquals(2, api.registrations)
        } finally {
            loops.forEach { it.cancelAndJoin() }
            withContext(Dispatchers.Main) { stores.forEach { it.clear() } }
            a.close(); b.close()
        }
    }
}
