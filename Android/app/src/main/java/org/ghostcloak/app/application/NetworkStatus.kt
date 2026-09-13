package org.ghostcloak.app.application

/** Last foreground network operation; this is not a background connectivity monitor. */
enum class NetworkStatus {
    DISABLED, NEEDS_CONNECT, CONNECTING, CONNECTED, SYNCING, RATE_LIMITED, OFFLINE, ERROR, RECOVERY_REQUIRED, RECOVERING
}
