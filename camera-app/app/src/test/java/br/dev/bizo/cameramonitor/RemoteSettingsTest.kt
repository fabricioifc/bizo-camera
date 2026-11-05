package br.dev.bizo.cameramonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSettingsTest {

    @Test
    fun `lente, qualidade e sensibilidade aceitam o nome em minúsculas`() {
        assertEquals(Lens.FRONT, RemoteSettings.parseLens("front"))
        assertEquals(Quality.HIGH, RemoteSettings.parseQuality("high"))
        assertEquals(Sensitivity.LOW, RemoteSettings.parseSensitivity("low"))
        assertEquals("back", RemoteSettings.name(Lens.BACK))
    }

    @Test
    fun `valores desconhecidos ou de outro tipo são ignorados`() {
        assertNull(RemoteSettings.parseLens("side"))
        assertNull(RemoteSettings.parseQuality(2))
        assertNull(RemoteSettings.parseSensitivity(null))
        assertNull(RemoteSettings.parseFlag("true"))
        assertNull(RemoteSettings.parseRotation(45))
        assertNull(RemoteSettings.parseRotation("90"))
    }

    @Test
    fun `rotação aceita múltiplos de 90 graus`() {
        assertEquals(Rotation.R0, RemoteSettings.parseRotation(0))
        assertEquals(Rotation.R90, RemoteSettings.parseRotation(90))
        assertEquals(Rotation.R270, RemoteSettings.parseRotation(270L))
        assertEquals(true, RemoteSettings.parseFlag(true))
    }

    @Test
    fun `rotação total soma sensor e escolha do usuário`() {
        assertEquals(90, totalRotation(90, 0))
        assertEquals(0, totalRotation(90, 270))
        assertEquals(180, totalRotation(270, 270))
        assertEquals(270, totalRotation(0, -90))
    }
}
