package br.dev.bizo.cameramonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipFramesTest {

    @Test
    fun `reduz para 480 no lado maior com medidas múltiplas de 16`() {
        assertEquals(480 to 368, ClipFrames.size(640, 480, 480))
        assertEquals(368 to 480, ClipFrames.size(480, 640, 480))
        assertEquals(480 to 272, ClipFrames.size(1280, 720, 480))
    }

    @Test
    fun `quadro pequeno não é ampliado`() {
        assertEquals(320 to 240, ClipFrames.size(320, 240, 480))
    }

    @Test
    fun `converte cores para YUV com o tamanho certo`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val pixels = intArrayOf(white, white, black, black, white, white, black, black)
        val yuv = ClipFrames.argbToI420(pixels, 4, 2)
        assertEquals(4 * 2 * 3 / 2, yuv.size)
        // Branco: Y alto; preto: Y baixo (faixa de vídeo 16 a 235).
        assertEquals(235, yuv[0].toInt() and 0xFF)
        assertEquals(16, yuv[2].toInt() and 0xFF)
        // Cinza sem cor: U e V no meio (128).
        assertEquals(128, yuv[8].toInt() and 0xFF)
        assertEquals(128, yuv[10].toInt() and 0xFF)
    }
}
