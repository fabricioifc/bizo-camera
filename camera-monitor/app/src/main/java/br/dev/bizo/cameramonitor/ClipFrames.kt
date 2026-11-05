package br.dev.bizo.cameramonitor

import kotlin.math.roundToInt

/** Contas do vídeo do movimento, separadas do Android para poderem ser testadas. */
object ClipFrames {

    /**
     * Tamanho do vídeo para um quadro [width] x [height]: lado maior até [maxSide],
     * mesma proporção e medidas múltiplas de 16 (exigência de muitos encoders H.264).
     */
    fun size(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(width, height))
        fun round16(value: Double) = maxOf(16, (value / 16).roundToInt() * 16)
        return round16(width * scale) to round16(height * scale)
    }

    /**
     * Converte pixels ARGB (Bitmap.getPixels) para YUV 4:2:0 planar (I420: Y, depois U, depois V),
     * com a fórmula BT.601. U e V usam um pixel a cada bloco de 2x2.
     */
    fun argbToI420(pixels: IntArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val out = ByteArray(frameSize * 3 / 2)
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                out[y * width + x] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    out[uIndex++] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    out[vIndex++] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
        return out
    }

    private fun clamp(value: Int) = value.coerceIn(0, 255)
}
