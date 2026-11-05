package br.dev.bizo.cameramonitor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

// Conversões dos quadros RGBA entregues pela câmera (ImageAnalysis com OUTPUT_IMAGE_FORMAT_RGBA_8888).

/** Converte o quadro em JPEG, já girado na orientação certa. */
fun ImageProxy.toJpeg(quality: Int): ByteArray {
    val plane = planes[0]
    val rowPadding = plane.rowStride - plane.pixelStride * width
    val raw = Bitmap.createBitmap(
        width + rowPadding / plane.pixelStride,
        height,
        Bitmap.Config.ARGB_8888
    )
    plane.buffer.rewind()
    raw.copyPixelsFromBuffer(plane.buffer)

    val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
    val frame = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, true)

    val out = ByteArrayOutputStream()
    frame.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** Grade de brilho (0 a 255) com [columns] x [rows] pontos espalhados pelo quadro. */
fun ImageProxy.lumaGrid(columns: Int, rows: Int): IntArray {
    val plane = planes[0]
    val buffer = plane.buffer
    val grid = IntArray(columns * rows)
    for (gy in 0 until rows) {
        val y = (gy * 2 + 1) * height / (rows * 2)
        for (gx in 0 until columns) {
            val x = (gx * 2 + 1) * width / (columns * 2)
            val offset = y * plane.rowStride + x * plane.pixelStride
            val r = buffer.get(offset).toInt() and 0xFF
            val g = buffer.get(offset + 1).toInt() and 0xFF
            val b = buffer.get(offset + 2).toInt() and 0xFF
            grid[gy * columns + gx] = (r * 299 + g * 587 + b * 114) / 1000
        }
    }
    return grid
}
