package br.dev.bizo.cameramonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

// Conversões dos quadros RGBA entregues pela câmera (ImageAnalysis com OUTPUT_IMAGE_FORMAT_RGBA_8888).

/** Converte o quadro em JPEG, já girado na orientação certa, mais a [extraRotation] escolhida pelo usuário. */
fun ImageProxy.toJpeg(quality: Int, extraRotation: Int = 0): ByteArray {
    val plane = planes[0]
    val rowPadding = plane.rowStride - plane.pixelStride * width
    val raw = Bitmap.createBitmap(
        width + rowPadding / plane.pixelStride,
        height,
        Bitmap.Config.ARGB_8888
    )
    plane.buffer.rewind()
    raw.copyPixelsFromBuffer(plane.buffer)

    val matrix = Matrix().apply { postRotate(totalRotation(imageInfo.rotationDegrees, extraRotation).toFloat()) }
    val frame = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, true)

    val out = ByteArrayOutputStream()
    frame.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/**
 * "Foto agora": gira o JPEG da câmera em [degrees], reduz para no máximo [maxSide] pixels
 * no lado maior e comprime de novo com [quality].
 */
fun rotateAndScaleJpeg(jpeg: ByteArray, degrees: Int, maxSide: Int, quality: Int): ByteArray {
    // Lê só o tamanho primeiro, para decodificar já reduzido (uma foto de 12 MP ocupa ~48 MB inteira).
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return jpeg

    val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate((((degrees % 360) + 360) % 360).toFloat())
    }
    val photo = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val out = ByteArrayOutputStream()
    photo.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** Bytes do JPEG entregue pelo ImageCapture (formato JPEG: um só plano com o arquivo). */
fun ImageProxy.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    buffer.rewind()
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

/** Rotação do sensor somada à escolhida pelo usuário, entre 0 e 359 graus. */
fun totalRotation(sensorDegrees: Int, extraDegrees: Int): Int = ((sensorDegrees + extraDegrees) % 360 + 360) % 360

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
