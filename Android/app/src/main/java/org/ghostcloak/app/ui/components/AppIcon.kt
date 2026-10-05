package org.ghostcloak.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import org.ghostcloak.app.R
import org.ghostcloak.app.ui.theme.GhostDimensions

/** Resource names are centralized here; screens supply semantic labels and theme colors. */
enum class Glyph(@param:DrawableRes val resource: Int) {
    CHAT(R.drawable.gc_chat), SEARCH(R.drawable.gc_search), SETTINGS(R.drawable.gc_settings),
    BACK(R.drawable.gc_back), SEND(R.drawable.gc_send), SHIELD(R.drawable.gc_verify),
    UNVERIFIED(R.drawable.gc_unverified),
    CLOSE(R.drawable.gc_close), MORE(R.drawable.gc_more), PERSON(R.drawable.gc_profile),
    CONTACTS(R.drawable.gc_contacts), COMPOSE(R.drawable.gc_new_chat),
    ATTACHMENT(R.drawable.gc_attachment), ADD_CONTACT(R.drawable.gc_add_contact),
    BLOCK(R.drawable.gc_block), CALL(R.drawable.gc_call), CAMERA(R.drawable.gc_camera),
    CHECK(R.drawable.gc_check), DELETE(R.drawable.gc_delete), EDIT(R.drawable.gc_edit),
    FAVORITE(R.drawable.gc_favorite), FINGERPRINT(R.drawable.gc_fingerprint),
    GALLERY(R.drawable.gc_gallery), GHOST(R.drawable.gc_ghost), HELP(R.drawable.gc_help),
    HOME(R.drawable.gc_home), LOGOUT(R.drawable.gc_logout), MICROPHONE(R.drawable.gc_microphone),
    NOTIFICATIONS(R.drawable.gc_notifications), PRIVACY(R.drawable.gc_privacy),
    REPORT(R.drawable.gc_report), SHARE(R.drawable.gc_share), VIDEO(R.drawable.gc_video)
}

/** Bundled local vectors inherit light/dark/system appearance, including disabled colors. */
@Composable fun AppIcon(glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painter=painterResource(glyph.resource),contentDescription=description,
        modifier=modifier.size(GhostDimensions.spacious),tint=tint)
}
