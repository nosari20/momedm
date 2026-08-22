package edu.fnosari.momedm.controller.provisioning

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Renders arbitrary text (the provisioning JSON payload) as a square black-on-white QR code bitmap. */
object QrBitmap {
    /** Encodes [text] as a QR code and rasterizes it to a [sizePx] x [sizePx] [Bitmap]. */
    fun render(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(sizePx * sizePx) { i -> if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.RGB_565)
    }
}
