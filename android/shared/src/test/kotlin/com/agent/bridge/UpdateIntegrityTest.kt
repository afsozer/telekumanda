package com.agent.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateIntegrityTest {
    // "abc"nin bilinen SHA-256 özeti (FIPS 180-2 örneği).
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `kopyalarken dogru ozeti hesaplar`() {
        val out = ByteArrayOutputStream()
        val digest = UpdateIntegrity.copyWithDigest(ByteArrayInputStream("abc".toByteArray()), out)
        assertEquals(abc, digest)
        assertEquals("abc", out.toString())
    }

    @Test
    fun `ozet tutarsa gecer, tutmazsa reddedilir`() {
        UpdateIntegrity.verify(abc, abc.uppercase())
        assertThrows(IOException::class.java) { UpdateIntegrity.verify(abc, "0".repeat(64)) }
    }

    @Test
    fun `ozetsiz ya da bozuk bildirim reddedilir`() {
        assertThrows(IOException::class.java) { UpdateIntegrity.expectedDigest(null) }
        assertThrows(IOException::class.java) { UpdateIntegrity.expectedDigest("") }
        assertThrows(IOException::class.java) { UpdateIntegrity.expectedDigest("abc") }
        assertThrows(IOException::class.java) { UpdateIntegrity.expectedDigest("z".repeat(64)) }
    }

    @Test
    fun `bildirimdeki buyuk harfli ozet kucuge cevrilir`() {
        assertEquals(abc, UpdateIntegrity.expectedDigest(" ${abc.uppercase()} "))
    }
}
