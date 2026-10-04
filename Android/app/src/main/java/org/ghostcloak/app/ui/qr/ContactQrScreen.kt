package org.ghostcloak.app.ui.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.ghostcloak.app.ui.components.PageContent
import org.ghostcloak.protocol.GhostCloakContactQr
import org.ghostcloak.protocol.GhostCloakIds

@Composable fun ContactQrScreen(id: String, back: () -> Unit) {
    val bitmap = remember(id) { contactQrBitmap(id).asImageBitmap() }
    PageContent("Your Ghost Cloak QR", "Scan to add this Ghost Cloak ID.", back) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap, "QR code for your Ghost Cloak ID", Modifier.size(288.dp).background(Color.White).padding(12.dp))
        }
        Text(GhostCloakIds.display(id), style = MaterialTheme.typography.titleLarge)
        Text("A scan adds an ID only. Compare safety numbers separately to verify a person.",
            style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun contactQrBitmap(id: String): Bitmap {
    val matrix = QRCodeWriter().encode(GhostCloakContactQr.encode(id), BarcodeFormat.QR_CODE, 512, 512,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}
