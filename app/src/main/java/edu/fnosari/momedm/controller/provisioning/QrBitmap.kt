package edu.fnosari.momedm.controller.provisioning

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrBitmap {
    fun render(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(sizePx * sizePx) { i -> if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.RGB_565)
    }
}
