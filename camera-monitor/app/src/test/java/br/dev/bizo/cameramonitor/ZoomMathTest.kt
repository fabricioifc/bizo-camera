package br.dev.bizo.cameramonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    @Test
    fun `grande-angular de 120 graus equivale a cerca de 0,6x de uma principal de 80 graus`() {
        val main = Math.toRadians(80.0)
        val wide = Math.toRadians(120.0)
        assertEquals(0.5f, ZoomMath.round(ZoomMath.equivalentZoom(main, wide)))
        assertEquals(0.6f, ZoomMath.round(ZoomMath.equivalentZoom(Math.toRadians(77.0), Math.toRadians(108.0))))
    }

    @Test
    fun `abaixo do minimo da principal usa a grande-angular, convertendo o zoom`() {
        // Pedido 0,6x com grande-angular de 0,6x: grande-angular em 1x.
        assertEquals(true to 1f, ZoomMath.choose(0.6f, 1f, 8f, 0.6f))
        // Pedido 0,9x: grande-angular com 1,5x.
        val (wide, ratio) = ZoomMath.choose(0.9f, 1f, 8f, 0.6f)
        assertEquals(true, wide)
        assertEquals(1.5f, ZoomMath.round(ratio))
    }

    @Test
    fun `de 1x para cima fica na principal, dentro da faixa`() {
        assertEquals(false to 2f, ZoomMath.choose(2f, 1f, 8f, 0.6f))
        assertEquals(false to 8f, ZoomMath.choose(20f, 1f, 8f, 0.6f))
        // Sem grande-angular, 0,6x vira o minimo da principal.
        assertEquals(false to 1f, ZoomMath.choose(0.6f, 1f, 8f, null))
        // Câmera lógica que já desce abaixo de 1x: fica nela.
        assertEquals(false to 0.6f, ZoomMath.choose(0.6f, 0.5f, 10f, 0.5f))
    }

    @Test
    fun `faixa e rotulo`() {
        assertEquals(0.6f to 8f, ZoomMath.range(1f, 8f, 0.6f))
        assertEquals(1f to 4f, ZoomMath.range(1f, 4f, null))
        assertEquals("0,6×", ZoomMath.label(0.6f))
        assertEquals("1×", ZoomMath.label(1f))
        assertEquals("2,5×", ZoomMath.label(2.5f))
    }
}
