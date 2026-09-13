package org.ghostcloak.app.application

import android.util.Log
import org.ghostcloak.app.BuildConfig
import java.io.File
import java.net.URI

/** Observes only the selected filenames. Never scans, opens, repairs or creates a store. */
internal object LocalStateDiagnostics {
    internal var sink: (String) -> Unit = { Log.d("GhostCloakStore", it); Unit }
    private fun emit(line: String) { try { sink(line) } catch (_: Exception) { } }

    internal fun originCategory(origin: String): String = when {
        origin.isEmpty() -> "empty"
        runCatching { URI(origin).host?.lowercase()?.trimEnd('.') == "api.ghostcloak.org" }.getOrDefault(false) -> "staging"
        // No production host has been designated. Never infer production from arbitrary input.
        else -> "other"
    }

    fun <T> open(directory: File, endpoint: String, origin: String, operation: () -> T): T {
        emit("PACKAGE_NAME=${BuildConfig.APPLICATION_ID}")
        emit("BUILD_TYPE=${BuildConfig.BUILD_TYPE}")
        emit("API_ORIGIN_CATEGORY=${originCategory(origin)}")
        emit("PROFILE_SLOT=${if (endpoint == "local") "default" else "nondefault"}")
        val presence = runCatching {
            File(directory, "$endpoint.db").exists() to File(directory, "$endpoint.wrapped").exists()
        }.getOrNull()
        if (presence != null) {
            emit("DATABASE_EXISTS=${presence.first}")
            emit("WRAPPED_KEY_EXISTS=${presence.second}")
            emit("ENDPOINT_STORE_EXISTS=${presence.first && presence.second}")
            if (!presence.first || !presence.second) emit("STORE_OPEN_RESULT=missing")
        } else emit("STORE_OPEN_RESULT=error")
        return try {
            operation().also {
                emit("STORE_OPEN_RESULT=${when (presence) {
                    true to true -> "existing"
                    false to false -> "new"
                    else -> "error"
                }}")
            }
        } catch (failure: Throwable) {
            emit("STORE_OPEN_RESULT=error")
            throw failure
        }
    }
}
