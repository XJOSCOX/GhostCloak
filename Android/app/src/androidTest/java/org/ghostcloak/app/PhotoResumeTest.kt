package org.ghostcloak.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.app.ui.screens.awaitPhotoHost
import org.junit.Assert.*
import org.junit.Test

class PhotoResumeTest {
    @Test fun realActivityMarksPresentationReadyBeforeStartObservers() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GhostApplication
        var starts=0
        val callback=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:Activity,state:Bundle?) {
                if(activity is MainActivity) activity.lifecycle.addObserver(LifecycleEventObserver { _,event ->
                    if(event==Lifecycle.Event.ON_START) { assertTrue(app.media.presentationVisible);starts++ }
                })
            }
            override fun onActivityStarted(a:Activity) {}
            override fun onActivityResumed(a:Activity) {}
            override fun onActivityPaused(a:Activity) {}
            override fun onActivityStopped(a:Activity) {}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle) {}
            override fun onActivityDestroyed(a:Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(app.media.presentationVisible)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(2,starts)
            }
        } finally { app.unregisterActivityLifecycleCallbacks(callback) }
    }
    @Test fun pickerResultWaitsForResumeAndDestroyedHostDoesNotSelect()=runBlocking {
        withContext(Dispatchers.Main) {
            class Host:LifecycleOwner {
                val registry=LifecycleRegistry(this)
                override val lifecycle:Lifecycle get()=registry
            }
            val host=Host();host.registry.currentState=Lifecycle.State.STARTED
            var selections=0
            val pending=launch(start=CoroutineStart.UNDISPATCHED) { awaitPhotoHost(host.lifecycle) {selections++} }
            assertEquals(0,selections)
            host.registry.currentState=Lifecycle.State.RESUMED;pending.join()
            assertEquals(1,selections)
            host.registry.currentState=Lifecycle.State.CREATED
            val cancelled=launch(start=CoroutineStart.UNDISPATCHED) { awaitPhotoHost(host.lifecycle) {selections++} }
            host.registry.currentState=Lifecycle.State.DESTROYED;cancelled.join()
            assertEquals(1,selections)
        }
    }
}
