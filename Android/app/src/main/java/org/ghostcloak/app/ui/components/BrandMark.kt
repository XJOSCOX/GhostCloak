package org.ghostcloak.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "Ghost Cloak emblem" }) {
        val w = size.width; val h = size.height
        val cloak = Path().apply {
            moveTo(w * .16f, h * .83f); lineTo(w * .23f, h * .39f)
            cubicTo(w * .28f, h * .03f, w * .72f, h * .03f, w * .77f, h * .39f)
            lineTo(w * .84f, h * .83f); lineTo(w * .65f, h * .74f)
            lineTo(w * .5f, h * .88f); lineTo(w * .35f, h * .74f); close()
        }
        drawPath(cloak, color, style = Stroke(w * .035f))
        drawCircle(color, w * .035f, androidx.compose.ui.geometry.Offset(w * .39f, h * .44f))
        drawCircle(color, w * .035f, androidx.compose.ui.geometry.Offset(w * .61f, h * .44f))
    }
}
