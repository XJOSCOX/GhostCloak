package org.ghostcloak.app

import android.graphics.Bitmap
import kotlinx.coroutines.*
import org.ghostcloak.app.attachments.*
import org.junit.Assert.*
import org.junit.Test

class InlinePhotosTest {
    private suspend fun await(check: () -> Boolean) { withTimeout(5000) { while(!check()) delay(10) } }
    @Test fun acceptedPhotoAutoFetchesOnceButRequestsDocumentsAndUnavailableDoNot()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var calls=0; var allowed=true; var available=true
        val photos=InlinePhotos(scope,{allowed},{_,_->available}) { _,_,verified ->
            calls++; verified(); Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888)
        }
        try {
            withContext(Dispatchers.Main) {
                photos.request("c","request",true,false)
                photos.request("c","document",false,true)
                allowed=false; photos.request("c","locked",true,true); allowed=true
                available=false; photos.request("c","expired",true,true); available=true
                assertEquals(0,calls); assertTrue(photos.state.value.isEmpty())
                photos.request("c","photo",true,true)
                photos.request("c","photo",true,true)
            }
            await { photos.state.value["c" to "photo"]?.stage==PhotoStage.READY }
            assertEquals(1,calls)
            withContext(Dispatchers.Main) { photos.request("c","photo",true,true) }
            assertEquals(1,calls)
        } finally { withContext(Dispatchers.Main) { photos.clear() }; scope.cancel() }
    }
    @Test fun lockedOrBackgroundDuringLoadDropsLatePixelsAndUnlockCanReload()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var allowed=true; var calls=0
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val photos=InlinePhotos(scope,{allowed},{_,_->true}) { _,_,verified ->
            calls++; entered.complete(Unit)
            // Simulate a decoder that cannot be interrupted mid-call.
            withContext(NonCancellable) { release.await() }
            verified(); Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        }
        try {
            withContext(Dispatchers.Main) { photos.request("c","photo",true,true) }
            entered.await()
            withContext(Dispatchers.Main) { allowed=false; photos.clear() }
            release.complete(Unit); delay(50)
            assertTrue(photos.state.value.isEmpty())
            withContext(Dispatchers.Main) { allowed=true; photos.request("c","photo",true,true) }
            await { photos.state.value["c" to "photo"]?.stage==PhotoStage.READY }
            assertEquals(2,calls)
            withContext(Dispatchers.Main) { allowed=false; photos.clear() }
            assertTrue(photos.state.value.isEmpty())
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun failuresStayLocalUntilExplicitRetryAndDeletionDropsReadyPixels()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var calls=0; var available=true
        val photos=InlinePhotos(scope,{true},{_,_->available}) { _,_,_ ->
            if(++calls==1) error("corrupt")
            Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        }
        try {
            withContext(Dispatchers.Main) { photos.request("c","photo",true,true) }
            await { photos.state.value["c" to "photo"]?.stage==PhotoStage.FAILED }
            withContext(Dispatchers.Main) { photos.request("c","photo",true,true) }
            assertEquals(1,calls)
            assertNull(photos.state.value["c" to "photo"]?.bitmap)
            withContext(Dispatchers.Main) { photos.request("c","photo",true,true,true) }
            await { photos.state.value["c" to "photo"]?.stage==PhotoStage.READY }
            withContext(Dispatchers.Main) { available=false; photos.reconcile() }
            assertTrue(photos.state.value.isEmpty())
        } finally { withContext(Dispatchers.Main) { photos.clear() }; scope.cancel() }
    }
}
