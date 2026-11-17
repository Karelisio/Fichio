package com.karelisio.fichio.transfer

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code rendering with zxing: one pixel per module (scale it up with nearest-neighbour filtering). */
object QrCode {
    fun bitmap(text: String, dark: Int = 0xFF000000.toInt(), light: Int = 0xFFFFFFFF.toInt()): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h) { i -> if (m[i % w, i / w]) dark else light }
        createBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }.getOrNull()
}
