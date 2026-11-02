package com.glasslauncher.tv.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object Net {
    /** Black modules on white, made on the device. [margin] is the white border in modules. */
    fun qr(text: String, sizePx: Int, margin: Int = 2): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to margin, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val px = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) for (x in 0 until sizePx) px[y * sizePx + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
