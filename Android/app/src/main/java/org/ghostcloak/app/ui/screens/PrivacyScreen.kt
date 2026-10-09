package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.GhostCloakIds

/** Preferences here are local to this encrypted installation, except the existing P8 OS setting. */
@Composable fun PrivacyScreen(state:AppState, back:()->Unit, change:(PrivacyDefaults)->Unit,
    requestPrivacy:(Boolean)->Unit,sharing:(Boolean)->Unit,appLock:()->Unit,
    inactiveProtection:()->Unit,safeExit:()->Unit,blockedContacts:()->Unit,
    clearCache:(()->Unit)->Unit,cacheBytes:((Long)->Unit)->Unit,buildInfo:()->Unit) {
    var confirmClear by remember {mutableStateOf(false)}
    var cacheSize by remember {mutableStateOf<Long?>(null)}
    PageContent("Privacy",back=back) {
        ErrorNotice(state.error,important=state.errorImportant)
        SettingsGroup("Messaging privacy") {
            Text("Default disappearing messages",style=MaterialTheme.typography.titleMedium)
            Text("Applies only when a new conversation is accepted. Existing chat timers stay as chosen. The actual chat timer is sent inside an encrypted control.",
                style=MaterialTheme.typography.bodySmall)
            ChoiceSetting("Default timer",DisappearingTimer.from(state.privacyDefaults.disappearingSeconds).label,
                DisappearingTimer.entries.map {it.label to it.seconds}) {change(state.privacyDefaults.copy(disappearingSeconds=it))}
            Text("Participating clients remove messages after the selected time. Copies saved elsewhere cannot be erased.",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Confirm message requests")
                    Text("Hide request content and attachments until acceptance.",style=MaterialTheme.typography.bodySmall)
                }
                Switch(state.requireRequestConfirmation,onCheckedChange=requestPrivacy,enabled=!state.loading,
                    modifier=Modifier.testTag("request-confirmation"))
            }
            Text("Changes affect future requests only. Existing hidden requests remain hidden.",style=MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Group invitations") {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow invitations from contacts")
                    Text("Accepted contacts may invite you to a group. You must still accept each invitation before joining.",
                        style=MaterialTheme.typography.bodySmall)
                }
                Switch(state.privacyDefaults.allowGroupInvitations,
                    onCheckedChange={change(state.privacyDefaults.copy(allowGroupInvitations=it))},
                    enabled=!state.loading,modifier=Modifier.testTag("allow-group-invitations"))
            }
            Text("Turn off to receive no new group invitations. Existing pending invitations are hidden and cannot be accepted until you turn this back on. Your current groups are unaffected.",
                style=MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Media privacy") {
            ChoiceSetting("Default voice mask",state.privacyDefaults.voiceMask.name.lowercase().replaceFirstChar(Char::uppercase),
                VoiceMaskPreference.entries.map {it.name.lowercase().replaceFirstChar(Char::uppercase) to it}) {
                change(state.privacyDefaults.copy(voiceMask=it))
            }
            Text("Preselected for each new voice note; you can override it before sending. Masking reduces recognizability but does not guarantee anonymity.",style=MaterialTheme.typography.bodySmall)
            val options=DownloadPreference.entries.map { (if(it==DownloadPreference.MANUAL) "Manual download" else "Auto-download") to it }
            ChoiceSetting("Photos",state.privacyDefaults.photos.label(),options) {change(state.privacyDefaults.copy(photos=it))}
            ChoiceSetting("Voice notes",state.privacyDefaults.voiceNotes.label(),options) {change(state.privacyDefaults.copy(voiceNotes=it))}
            ChoiceSetting("Documents",state.privacyDefaults.documents.label(),options) {change(state.privacyDefaults.copy(documents=it))}
            Text("Automatic downloads use the current connection only after an accepted, authenticated message. Hidden requests, blocked contacts, expired media and View Once stay manual or unavailable.",style=MaterialTheme.typography.bodySmall)
            Text("Downloaded media stays cached until its message is removed or expires. Decrypted temporary files are cleaned after use; exported copies are outside Ghost Cloak.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={cacheBytes {cacheSize=it}}) {Text("Check downloaded cache size")}
            cacheSize?.let {Text("Downloaded cache: ${it/1024} KiB",style=MaterialTheme.typography.bodySmall)}
            OutlinedButton(onClick={confirmClear=true}) {Text("Clear downloaded media cache")}
        }
        SettingsGroup("Profile privacy") {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share profile with accepted contacts")
                    Text("Name, About and photo are shared only through end-to-end encryption after acceptance.",style=MaterialTheme.typography.bodySmall)
                }
                Switch(state.ownProfile.sharing,onCheckedChange=sharing,enabled=!state.loading,
                    modifier=Modifier.testTag("profile-sharing"))
            }
            Text("When off, accepted contacts stop receiving future profile updates. Previously received details cannot be erased from their devices. Re-enabling sends your current profile.",style=MaterialTheme.typography.bodySmall)
        }
        if(!state.demo && state.networkConfigured) NotificationSettings()
        SettingsGroup("Device protection") {
            Text("Ghost Cloak prevents screenshots and task previews on sensitive screens. View Once protection remains active.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=blockedContacts) {Text("Blocked contacts")}
            if(org.ghostcloak.app.access.LocalAppLock.current!=null) {
                OutlinedButton(onClick=appLock) {Text("App Lock")}
                OutlinedButton(onClick=inactiveProtection) {Text("Inactive Device Protection")}
                if(org.ghostcloak.app.BuildConfig.EMERGENCY_PIN_ARMING_ENABLED)
                    OutlinedButton(onClick=safeExit) {Text("Safe Exit")}
            }
            Text("Copied text is marked sensitive for Android's clipboard preview. Android does not provide reliable clipboard auto-clear without risking a newer copy, so Ghost Cloak does not clear it automatically.",style=MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("Account and identity") {
            Text("Display name: ${state.identity?.displayName.orEmpty()}")
            state.ghostCloakId?.let {Text("Ghost Cloak ID: ${GhostCloakIds.display(it)}")}
            Text("Profile sharing: ${if(state.ownProfile.sharing) "On" else "Off"}")
            Text("Your safety number verifies a specific contact identity. Compare it with that person in Contact security before marking them verified.",style=MaterialTheme.typography.bodySmall)
            Text("Ghost Cloak does not currently provide cloud chat backup.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=buildInfo) {Text("Build information")}
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("Clear downloaded media cache?")},
        text={Text("Messages and attachment details remain. Media may be downloaded again only while a server copy is still available. Sent uploads and copies exported outside Ghost Cloak are not removed.")},
        confirmButton={TextButton(onClick={confirmClear=false;clearCache {cacheBytes {cacheSize=it}}}) {Text("Clear cache")}},
        dismissButton={TextButton(onClick={confirmClear=false}) {Text("Cancel")}})
}

private fun DownloadPreference.label()=if(this==DownloadPreference.MANUAL) "Manual download" else "Auto-download"

@Composable private fun <T> ChoiceSetting(title:String,value:String,options:List<Pair<String,T>>,onChoose:(T)->Unit) {
    var open by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick={open=true},modifier=Modifier.testTag("privacy-choice-$title")) {Text(value)}
    }
    if(open) AlertDialog(onDismissRequest={open=false},title={Text(title)},text={
        Column {options.forEach { (label,option) ->
            TextButton(onClick={onChoose(option);open=false},modifier=Modifier.fillMaxWidth()) {Text(label)}
        }}
    },confirmButton={TextButton(onClick={open=false}) {Text("Done")}})
}
