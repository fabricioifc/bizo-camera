package br.dev.bizo.cameramonitor

import br.dev.bizo.cameramonitor.DeviceOrientation.ROTATION_0
import br.dev.bizo.cameramonitor.DeviceOrientation.ROTATION_180
import br.dev.bizo.cameramonitor.DeviceOrientation.ROTATION_270
import br.dev.bizo.cameramonitor.DeviceOrientation.ROTATION_90
import br.dev.bizo.cameramonitor.DeviceOrientation.rotationFor
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOrientationTest {

    @Test
    fun `cada posição do celular vira a rotação do CameraX`() {
        assertEquals(ROTATION_0, rotationFor(0, ROTATION_0))
        assertEquals(ROTATION_270, rotationFor(90, ROTATION_0))
        assertEquals(ROTATION_180, rotationFor(180, ROTATION_0))
        assertEquals(ROTATION_90, rotationFor(270, ROTATION_0))
        assertEquals(ROTATION_0, rotationFor(350, ROTATION_90))
    }

    @Test
    fun `perto da diagonal mantém a posição atual`() {
        // 55° ainda está dentro da margem de "em pé"; 65° já passou.
        assertEquals(ROTATION_0, rotationFor(55, ROTATION_0))
        assertEquals(ROTATION_270, rotationFor(65, ROTATION_0))
        // Voltando: 35° ainda conta como deitado.
        assertEquals(ROTATION_270, rotationFor(35, ROTATION_270))
        assertEquals(ROTATION_0, rotationFor(25, ROTATION_270))
    }

    @Test
    fun `celular deitado de costas (ângulo desconhecido) mantém a posição`() {
        assertEquals(ROTATION_90, rotationFor(-1, ROTATION_90))
    }
}
