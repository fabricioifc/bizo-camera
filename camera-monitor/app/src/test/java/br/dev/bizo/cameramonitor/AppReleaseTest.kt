package br.dev.bizo.cameramonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {

    private fun release(packageName: String = "br.dev.bizo.cameramonitor", versionCode: Int = 50) =
        AppRelease(packageName, versionCode, "0.4.0", size = 1_000, sha256 = "abc")

    @Test
    fun `versão maior do mesmo app é atualização`() {
        assertTrue(release(versionCode = 50).isUpdateFor("br.dev.bizo.cameramonitor", 49))
    }

    @Test
    fun `versão igual ou menor não é atualização`() {
        assertFalse(release(versionCode = 50).isUpdateFor("br.dev.bizo.cameramonitor", 50))
        assertFalse(release(versionCode = 40).isUpdateFor("br.dev.bizo.cameramonitor", 50))
    }

    @Test
    fun `APK de outro pacote não é atualização (ex - release no app debug)`() {
        assertFalse(release().isUpdateFor("br.dev.bizo.cameramonitor.debug", 1))
    }
}
