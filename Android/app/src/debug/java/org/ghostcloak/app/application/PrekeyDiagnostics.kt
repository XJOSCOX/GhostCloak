package org.ghostcloak.app.application

internal object PrekeyDiagnostics {
    fun emit(value: String) {
        if (value in setOf("PREKEY_POOL_STATE=healthy", "PREKEY_POOL_STATE=low", "PREKEY_POOL_STATE=empty",
            "PREKEY_POOL_STATE=unknown", "PREKEY_REFILL_START", "PREKEY_REFILL_OK", "PREKEY_REFILL_FAILED=auth",
            "PREKEY_REFILL_FAILED=rate_limit", "PREKEY_REFILL_FAILED=conflict", "PREKEY_REFILL_FAILED=unsupported",
            "PREKEY_REFILL_FAILED=retryable")) android.util.Log.d("GhostCloakNet", value)
    }
}
