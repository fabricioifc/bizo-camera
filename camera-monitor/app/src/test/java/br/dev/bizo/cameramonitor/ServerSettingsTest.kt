package br.dev.bizo.cameramonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsTest {

    @Test
    fun `aceita o endereço com ou sem https e com barra no fim`() {
        assertEquals("https://camera.bizo.dev.br", ServerSettings.normalize("https://camera.bizo.dev.br"))
        assertEquals("https://camera.bizo.dev.br", ServerSettings.normalize("camera.bizo.dev.br"))
        assertEquals("https://camera.bizo.dev.br", ServerSettings.normalize("  https://Camera.Bizo.dev.br/  "))
    }

    @Test
    fun `mantém a porta`() {
        assertEquals("https://camera.exemplo.com:8443", ServerSettings.normalize("camera.exemplo.com:8443"))
    }

    @Test
    fun `http só em build de teste`() {
        assertNull(ServerSettings.normalize("http://192.168.1.11:8787"))
        assertEquals("http://192.168.1.11:8787", ServerSettings.normalize("http://192.168.1.11:8787", allowHttp = true))
        assertEquals("http://192.168.1.11:8787", ServerSettings.normalize("192.168.1.11:8787", allowHttp = true))
    }

    @Test
    fun `recusa endereço inválido, com caminho ou com outro protocolo`() {
        assertNull(ServerSettings.normalize(""))
        assertNull(ServerSettings.normalize("   "))
        assertNull(ServerSettings.normalize("https://"))
        assertNull(ServerSettings.normalize("https://camera.exemplo.com/api"))
        assertNull(ServerSettings.normalize("https://camera.exemplo.com?x=1"))
        assertNull(ServerSettings.normalize("ftp://camera.exemplo.com"))
        assertNull(ServerSettings.normalize("https://user:senha@camera.exemplo.com"))
        assertNull(ServerSettings.normalize("camera exemplo.com"))
    }
}
