package org.ghostcloak.app

/** Debug-only, unexported, synthetic one-shot pipe for provider interoperability tests. */
class PhotoPipeProvider:android.content.ContentProvider() {
    companion object { var payload=byteArrayOf();val opens=java.util.concurrent.atomic.AtomicInteger() }
    override fun onCreate()=true
    override fun getType(uri:android.net.Uri)="application/octet-stream"
    override fun query(uri:android.net.Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):android.database.Cursor?=null
    override fun insert(uri:android.net.Uri,v:android.content.ContentValues?):android.net.Uri?=null
    override fun update(uri:android.net.Uri,v:android.content.ContentValues?,s:String?,a:Array<out String>?)=0
    override fun delete(uri:android.net.Uri,s:String?,a:Array<out String>?)=0
    override fun openFile(uri:android.net.Uri,mode:String):android.os.ParcelFileDescriptor {
        check(opens.incrementAndGet()==1 && mode=="r")
        val pipe=android.os.ParcelFileDescriptor.createPipe()
        val bytes=payload.copyOf()
        Thread {
            try { android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {it.write(bytes)} }
            finally {bytes.fill(0)}
        }.start()
        return pipe[0]
    }
}
