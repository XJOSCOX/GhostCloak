package org.ghostcloak.messaging

import kotlinx.serialization.Serializable

/** Local encrypted preferences; no value is included in a directory or server request. */
@Serializable enum class VoiceMaskPreference { ORIGINAL, SUBTLE, STRONG, SYNTHETIC }
@Serializable enum class DownloadPreference { MANUAL, AUTOMATIC }

@Serializable data class PrivacyDefaults(
    val disappearingSeconds: Int = 0,
    val voiceMask: VoiceMaskPreference = VoiceMaskPreference.ORIGINAL,
    val photos: DownloadPreference = DownloadPreference.MANUAL,
    val voiceNotes: DownloadPreference = DownloadPreference.MANUAL,
    val documents: DownloadPreference = DownloadPreference.MANUAL,
) {
    fun validated(): PrivacyDefaults = also { DisappearingTimer.from(disappearingSeconds) }
}
