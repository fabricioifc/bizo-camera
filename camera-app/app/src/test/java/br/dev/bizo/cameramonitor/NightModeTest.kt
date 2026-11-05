package br.dev.bizo.cameramonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightModeTest {

    @Test
    fun `escolhe a faixa que deixa cair mais os quadros por segundo`() {
        val ranges = listOf(15 to 15, 7 to 30, 30 to 30, 7 to 15, 15 to 30)
        assertEquals(7 to 30, NightMode.fpsRange(ranges))
    }

    @Test
    fun `ignora faixas invalidas e lista vazia`() {
        assertNull(NightMode.fpsRange(emptyList()))
        assertEquals(10 to 30, NightMode.fpsRange(listOf(0 to 30, 20 to 5, 10 to 30)))
    }

    @Test
    fun `compensacao em dois tercos do maximo`() {
        assertEquals(8, NightMode.exposureIndex(-12, 12))
        assertEquals(2, NightMode.exposureIndex(-2, 2))
        assertEquals(1, NightMode.exposureIndex(-1, 1))
        assertEquals(0, NightMode.exposureIndex(0, 0))
    }
}
