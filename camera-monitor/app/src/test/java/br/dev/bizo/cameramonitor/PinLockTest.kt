package br.dev.bizo.cameramonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {

    @Test
    fun `aceita PIN de 4 a 8 números`() {
        assertTrue(PinLock.isValidFormat("1234"))
        assertTrue(PinLock.isValidFormat("12345678"))
    }

    @Test
    fun `recusa PIN curto, longo ou com letras`() {
        assertFalse(PinLock.isValidFormat(""))
        assertFalse(PinLock.isValidFormat("123"))
        assertFalse(PinLock.isValidFormat("123456789"))
        assertFalse(PinLock.isValidFormat("12a4"))
        assertFalse(PinLock.isValidFormat("12 34"))
    }
}
