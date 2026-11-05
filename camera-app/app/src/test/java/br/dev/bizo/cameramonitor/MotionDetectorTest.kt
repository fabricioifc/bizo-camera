package br.dev.bizo.cameramonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val size = 32 * 24
    private val fraction = Sensitivity.MEDIUM.changedFraction

    /** Grade com fundo uniforme e um bloco claro de [blockSize] pontos a partir de [start]. */
    private fun grid(background: Int = 100, start: Int = 0, blockSize: Int = 0, block: Int = 220) =
        IntArray(size) { i -> if (i in start until start + blockSize) block else background }

    @Test
    fun `primeira conferência só serve de referência`() {
        val detector = MotionDetector()
        assertFalse(detector.update(grid(blockSize = 200), fraction))
    }

    @Test
    fun `imagem parada não é movimento`() {
        val detector = MotionDetector()
        repeat(5) { assertFalse(detector.update(grid(), fraction)) }
    }

    @Test
    fun `movimento exige duas conferências seguidas com mudança`() {
        val detector = MotionDetector()
        detector.update(grid(), fraction)
        // Um objeto aparece e depois se desloca: duas mudanças seguidas.
        assertFalse(detector.update(grid(start = 0, blockSize = 100), fraction))
        assertTrue(detector.update(grid(start = 300, blockSize = 100), fraction))
    }

    @Test
    fun `mudança isolada não dispara`() {
        val detector = MotionDetector()
        detector.update(grid(), fraction)
        assertFalse(detector.update(grid(start = 0, blockSize = 100), fraction))
        // Na conferência seguinte a imagem fica igual: a sequência zera.
        assertFalse(detector.update(grid(start = 0, blockSize = 100), fraction))
        assertFalse(detector.update(grid(start = 0, blockSize = 100), fraction))
    }

    @Test
    fun `luz acesa (brilho geral) não é movimento`() {
        val detector = MotionDetector()
        detector.update(grid(background = 60), fraction)
        assertFalse(detector.update(grid(background = 140), fraction))
        assertFalse(detector.update(grid(background = 200), fraction))
    }

    @Test
    fun `mudança pequena demais para a sensibilidade não dispara`() {
        val detector = MotionDetector()
        detector.update(grid(), fraction)
        // 10 de 768 pontos (~1,3%) mudam, abaixo dos 4% da sensibilidade média.
        assertFalse(detector.update(grid(start = 0, blockSize = 10), fraction))
        assertFalse(detector.update(grid(start = 400, blockSize = 10), fraction))
    }

    @Test
    fun `sensibilidade alta pega mudanças menores`() {
        val detector = MotionDetector()
        val high = Sensitivity.HIGH.changedFraction
        detector.update(grid(), high)
        // 20 de 768 pontos (~2,6%): passa dos 2% da sensibilidade alta.
        assertFalse(detector.update(grid(start = 0, blockSize = 20), high))
        assertTrue(detector.update(grid(start = 400, blockSize = 20), high))
    }

    @Test
    fun `reset faz a próxima conferência virar referência`() {
        val detector = MotionDetector()
        detector.update(grid(), fraction)
        assertFalse(detector.update(grid(start = 0, blockSize = 100), fraction))
        detector.reset()
        // Mesmo com mudança grande, a primeira grade depois do reset não conta.
        assertFalse(detector.update(grid(start = 300, blockSize = 100), fraction))
        assertFalse(detector.update(grid(start = 0, blockSize = 100), fraction))
        assertTrue(detector.update(grid(start = 300, blockSize = 100), fraction))
    }

    @Test
    fun `grade de tamanho diferente recomeça a comparação`() {
        val detector = MotionDetector()
        detector.update(grid(), fraction)
        assertFalse(detector.update(IntArray(10) { 255 }, fraction))
    }
}
