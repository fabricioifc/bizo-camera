package br.dev.bizo.cameramonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalLimitTest {

    @Test
    fun `sem temperatura ou abaixo de 40 graus não limita`() {
        assertEquals(ThermalLimit.NONE, ThermalLimit.forTemperature(null))
        assertEquals(ThermalLimit.NONE, ThermalLimit.forTemperature(25.0))
        assertEquals(ThermalLimit.NONE, ThermalLimit.forTemperature(39.9))
        assertFalse(ThermalLimit.forTemperature(39.9).throttled)
    }

    @Test
    fun `entre 40 e 44 graus cai para cerca de 4 quadros por segundo`() {
        val limit = ThermalLimit.forTemperature(40.0)
        assertEquals(250L, limit.minFrameIntervalMs)
        assertEquals(50, limit.maxJpegQuality)
        assertTrue(limit.throttled)
        assertEquals(limit, ThermalLimit.forTemperature(43.9))
    }

    @Test
    fun `a partir de 44 graus cai para 1 quadro por segundo`() {
        val limit = ThermalLimit.forTemperature(44.0)
        assertEquals(1_000L, limit.minFrameIntervalMs)
        assertEquals(40, limit.maxJpegQuality)
        assertTrue(limit.throttled)
        assertEquals(limit, ThermalLimit.forTemperature(60.0))
    }
}
