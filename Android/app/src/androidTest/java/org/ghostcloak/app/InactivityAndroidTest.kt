package org.ghostcloak.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.access.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InactivityAndroidTest {
    @Test fun atomicRecordSurvivesReopenAndCorruptionFailsClosed() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"inactivity-test-${System.nanoTime()}").apply {mkdirs()}
        val context=object: ContextWrapper(base) {override fun getNoBackupFilesDir()=root}
        try {
            val first=DurableInactivityStore(context)
            val record=InactivityRecord(InactivityPeriod.DAYS_30,4,1000L,1_700_000_000_000L,1_700_000_000_000L)
            assertNull(first.read())
            first.write(record)
            assertEquals(record,DurableInactivityStore(context).read())
            File(root,"inactive-protection.v1").writeBytes(byteArrayOf(1,2,3))
            assertEquals(InactivityAccess.UNAVAILABLE,InactivityProtection(DurableInactivityStore(context),
                {InactivityObservation(4,2000L,record.authenticatedWall)},{error("must_not_arm")}).check())
        } finally {root.listFiles()?.forEach {it.delete()};root.delete()}
    }
}
