package com.harmonic.app

import android.graphics.Color
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun joinCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return buildString(6) {
        repeat(6) { append(alphabet.random()) }
    }
}

fun qrImage(text: String, size: Int = 480): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (y in 0 until size) {
        for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap.asImageBitmap()
}

fun encodeChannels(v: DoubleArray, a: DoubleArray, h: DoubleArray): String {
    val n = minOf(v.size, a.size, h.size)
    val bytes = java.nio.ByteBuffer.allocate(n * 3 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) {
        bytes.putFloat(v[i].toFloat())
        bytes.putFloat(a[i].toFloat())
        bytes.putFloat(h[i].toFloat())
    }
    return android.util.Base64.encodeToString(bytes.array(), android.util.Base64.NO_WRAP)
}

fun decodeChannels(b64: String, n: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
    val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
    val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val v = DoubleArray(n)
    val a = DoubleArray(n)
    val h = DoubleArray(n)
    for (i in 0 until n) {
        v[i] = buf.float.toDouble()
        a[i] = buf.float.toDouble()
        h[i] = buf.float.toDouble()
    }
    return Triple(v, a, h)
}
