package org.ghostcloak.app.application

import android.app.Application

/** A single store/engine owner per process; activity recreation never opens a second engine. */
class GhostApplication : Application() {
    val runtime by lazy { AppRuntime(this) }
}
