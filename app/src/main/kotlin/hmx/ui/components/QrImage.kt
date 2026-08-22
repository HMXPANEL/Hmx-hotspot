package hmx.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrImage(payload: String, size: Dp = 220.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { encodeQr(payload, 640) }
    if (bitmap == null) {
        Box(modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium))
        return
    }
    Box(
        modifier
            .size(size)
            .background(Color.White, MaterialTheme.shapes.medium)
            .padding(10.dp),
    ) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.size(size - 20.dp))
    }
}

private fun encodeQr(payload: String, pixels: Int): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val bmp = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
    for (x in 0 until pixels) {
        for (y in 0 until pixels) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
}.getOrNull()
