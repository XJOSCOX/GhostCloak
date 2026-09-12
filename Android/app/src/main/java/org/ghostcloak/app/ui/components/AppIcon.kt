package org.ghostcloak.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ghostcloak.app.ui.theme.GhostDimensions

enum class Glyph { CHAT, SEARCH, SETTINGS, BACK, SEND, PLUS, SHIELD, CLOSE, MORE, PERSON, CONTACTS, COMPOSE }

/** Small local vector set; no icon font, downloaded assets or additional dependencies. */
@Composable fun AppIcon(glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(GhostDimensions.spacious).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round)
            fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(tint, Offset(a,b), Offset(c,d), 1.8f, StrokeCap.Round)
            when (glyph) {
                Glyph.COMPOSE -> {
                    drawPath(Path().apply { moveTo(11f,4f); lineTo(5f,4f); quadraticTo(3f,4f,3f,6f); lineTo(3f,19f); quadraticTo(3f,21f,5f,21f); lineTo(18f,21f); quadraticTo(20f,21f,20f,19f); lineTo(20f,13f) },tint,style=stroke)
                    drawPath(Path().apply { moveTo(10f,11f); lineTo(18f,3f); lineTo(21f,6f); lineTo(13f,14f); lineTo(9f,15f); close() },tint,style=stroke)
                    line(16f,5f,19f,8f)
                }
                Glyph.PLUS -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
                Glyph.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                Glyph.BACK -> {
                    drawPath(Path().apply { moveTo(15f,5f); lineTo(8f,12f); lineTo(15f,19f) },
                        tint, style = Stroke(1.8f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                Glyph.SEARCH -> { drawCircle(tint,6.5f,Offset(10.5f,10.5f),style=stroke); line(16f,16f,21f,21f) }
                Glyph.MORE -> listOf(5f,12f,19f).forEach { drawCircle(tint,1.5f,Offset(12f,it)) }
                Glyph.SEND -> { drawPath(Path().apply { moveTo(4f,4f); lineTo(21f,12f); lineTo(4f,20f); lineTo(7f,12f); close() },tint,style=stroke); line(7f,12f,15f,12f) }
                Glyph.CHAT -> { drawPath(Path().apply { moveTo(5f,4f); lineTo(19f,4f); quadraticTo(21f,4f,21f,6f); lineTo(21f,16f); quadraticTo(21f,18f,19f,18f); lineTo(9f,18f); lineTo(4f,21f); lineTo(4f,18f); quadraticTo(3f,18f,3f,16f); lineTo(3f,6f); quadraticTo(3f,4f,5f,4f); close() },tint,style=stroke); line(7f,9f,17f,9f); line(7f,13f,14f,13f) }
                Glyph.SHIELD -> { drawPath(Path().apply { moveTo(12f,3f); lineTo(20f,6f); lineTo(20f,12f); quadraticTo(20f,18f,12f,22f); quadraticTo(4f,18f,4f,12f); lineTo(4f,6f); close() },tint,style=stroke); line(8f,12f,11f,15f); line(11f,15f,16f,9f) }
                Glyph.SETTINGS -> { drawCircle(tint,7f,Offset(12f,12f),style=stroke); drawCircle(tint,2.5f,Offset(12f,12f),style=stroke); for(i in 0..7) { val a=i*Math.PI/4; line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) } }
                Glyph.CONTACTS -> {
                    drawCircle(tint,3f,Offset(9f,7f),style=stroke)
                    drawPath(Path().apply {moveTo(2f,21f); cubicTo(2f,12f,16f,12f,16f,21f)},tint,style=stroke)
                    drawPath(Path().apply {moveTo(16f,4f); cubicTo(21f,4f,21f,10f,16f,10f); moveTo(18f,14f); quadraticTo(22f,15f,22f,21f)},tint,style=stroke)
                }
                Glyph.PERSON -> { drawCircle(tint,4f,Offset(12f,7f),style=stroke); drawPath(Path().apply {moveTo(4f,21f); cubicTo(4f,12f,20f,12f,20f,21f)},tint,style=stroke) }
            }
        }
    }
}
