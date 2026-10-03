package com.wkhan.hexis.web.ui

import android.graphics.Bitmap
import android.graphics.Color

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders a string as a QR bitmap (for the pairing URL). */
object QrGen {
    fun bitmap(text: String, size: Int): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }.getOrNull()
}
