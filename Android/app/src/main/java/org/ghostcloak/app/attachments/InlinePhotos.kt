package org.ghostcloak.app.attachments

import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class PhotoStage { WAITING, FETCHING, VERIFYING, READY, FAILED, EXPIRED }
data class InlinePhoto(val stage: PhotoStage = PhotoStage.WAITING, val bitmap: Bitmap? = null, val storageFull: Boolean = false) {
    override fun toString() = "InlinePhoto(redacted)"
}

/** Main-thread owner of bounded, ephemeral thumbnails. No disk cache, descriptors or credentials.
 * Eligibility is rechecked by the runtime/store at every transfer and presentation boundary. */
class InlinePhotos(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val available: (String, String) -> Boolean,
    private val load: suspend (String, String, () -> Unit) -> Bitmap,
) {
    private val mutable = MutableStateFlow<Map<Pair<String,String>, InlinePhoto>>(emptyMap())
    val state = mutable.asStateFlow()
    private val jobs = mutableMapOf<Pair<String,String>, Job>()
    private val tokens = mutableMapOf<Pair<String,String>, Any>()
    private val visible = mutableSetOf<Pair<String,String>>()
    private val recent = LinkedHashSet<Pair<String,String>>()
    private val serial = Semaphore(1)
    private var generation = 0L
    private fun discard(key: Pair<String,String>) {
        visible.remove(key)
        recent.remove(key)
        tokens.remove(key)
        jobs.remove(key)?.cancel()
        mutable.value=mutable.value-key
    }
    private fun touch(key: Pair<String,String>) { recent.remove(key);recent.add(key) }
    fun request(conversation: String, message: String, photo: Boolean, accepted: Boolean, retry: Boolean = false) {
        val key=conversation to message
        if(!photo || !accepted || !allowed() || !available(conversation,message)) return
        if(!retry && (jobs[key]?.isActive==true || key in mutable.value)) {
            visible.add(key);touch(key);return
        }
        if(retry) discard(key)
        if(mutable.value.size>=12) {
            val victim=recent.firstOrNull {it !in visible} ?: return
            discard(victim)
        }
        visible.add(key);touch(key)
        val epoch=generation
        val token=Any(); tokens[key]=token
        fun valid()=epoch==generation && tokens[key]===token && allowed() && available(conversation,message)
        fun publish(value: InlinePhoto) { if(valid()) mutable.value=mutable.value+(key to value) }
        publish(InlinePhoto())
        jobs[key]=scope.launch {
            try {
                serial.withPermit {
                    ensureActive(); check(valid())
                    publish(InlinePhoto(PhotoStage.FETCHING))
                    val image=load(conversation,message) { publish(InlinePhoto(PhotoStage.VERIFYING)) }
                    ensureActive()
                    if(valid()) publish(InlinePhoto(PhotoStage.READY,image))
                }
            } catch(e: CancellationException) { publish(InlinePhoto(PhotoStage.FAILED)); throw e }
            catch(e: Exception) { publish(InlinePhoto(PhotoStage.FAILED,storageFull=e.message=="photo_cache_full")) }
            catch(_: OutOfMemoryError) { publish(InlinePhoto(PhotoStage.FAILED)) }
        }
    }
    fun release(conversation: String, message: String) {
        val key=conversation to message
        visible.remove(key)
        // Keep only verified, bounded thumbnails for a quick revisit in this unlocked foreground session.
        if(mutable.value[key]?.stage!=PhotoStage.READY || !allowed() || !available(conversation,message))
            discard(key)
    }
    fun clear() {
        generation++
        visible.clear();recent.clear()
        tokens.clear()
        jobs.values.forEach { it.cancel() }; jobs.clear()
        mutable.value=emptyMap() // Drop bitmap references; no persistent plaintext thumbnails.
    }
    fun reconcile() {
        mutable.value.keys.filter { !allowed() || !available(it.first,it.second) }
            .forEach(::discard)
    }
}
