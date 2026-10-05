package org.ghostcloak.app.attachments

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import org.ghostcloak.attachments.AttachmentKind
import java.io.File

/** One foreground AAC recorder. All output stays in the no-backup plaintext scratch until encryption. */
internal class VoiceCapture(private val context:Context,private val scratch:PlaintextScratch) {
    data class Recorded(val file:File,val durationMillis:Long,val bytes:Long)
    private var recorder:MediaRecorder?=null
    private var target:File?=null
    private var startedAt=0L
    val recording get()=recorder!=null
    val elapsedMillis get()=if(recording) (SystemClock.elapsedRealtime()-startedAt).coerceIn(0,300_000) else 0L

    fun start(onLimit:()->Unit,onError:()->Unit):File {
        check(recorder==null)
        val file=scratch.newFile()
        var media:MediaRecorder?=null
        try {
            @Suppress("DEPRECATION")
            val active=if(Build.VERSION.SDK_INT>=31) MediaRecorder(context) else MediaRecorder()
            media=active
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioChannels(1)
            active.setAudioSamplingRate(16000)
            active.setAudioEncodingBitRate(32000)
            active.setMaxDuration(300_000)
            active.setMaxFileSize(AttachmentKind.VOICE_NOTE.maximumBytes)
            active.setOutputFile(file.absolutePath)
            active.setOnInfoListener { _,what,_ -> if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit() }
            active.setOnErrorListener { _,_,_ -> onError() }
            active.prepare();active.start()
            target=file;recorder=active;startedAt=SystemClock.elapsedRealtime()
            return file
        } catch (failure:Exception) {
            try {media?.reset();media?.release()} catch (_:Exception) {}
            scratch.delete(file)
            throw failure
        }
    }
    fun stop():Recorded? {
        val media=recorder ?: return null
        recorder=null
        val file=target;target=null
        val duration=(SystemClock.elapsedRealtime()-startedAt).coerceAtLeast(0)
        val stopped=try {media.stop();true} catch (_:RuntimeException) {false}
        try {media.reset();media.release()} catch (_:Exception) {}
        val bytes=file?.length() ?: 0
        if(!stopped || file==null || duration<500 || bytes !in 1..AttachmentKind.VOICE_NOTE.maximumBytes) {
            file?.let(scratch::delete)
            return null
        }
        return Recorded(file,duration,bytes)
    }
    fun cancel() {
        val media=recorder;recorder=null
        val file=target;target=null
        try {media?.reset();media?.release()} catch (_:Exception) {}
        file?.let(scratch::delete)
    }
}
