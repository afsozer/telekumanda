package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIdentityTest {
    private val evFp = "192.168.1.0/24@192.168.1.1"
    private val otelFp = "10.0.0.0/24@10.0.0.1"

    @Test
    fun sameNameMatches() {
        val kayit = NetworkIdentity(ssid = "Ev", fingerprint = evFp)
        val simdi = NetworkIdentity(ssid = "Ev", fingerprint = evFp)

        assertTrue(kayit.matches(simdi))
    }

    // Alt ağ DHCP ile ya da router değişince kayabilir; ad aynıysa ağ aynıdır.
    @Test
    fun nameWinsOverChangedFingerprint() {
        val kayit = NetworkIdentity(ssid = "Ev", fingerprint = evFp)
        val simdi = NetworkIdentity(ssid = "Ev", fingerprint = "192.168.2.0/24@192.168.2.1")

        assertTrue(kayit.matches(simdi))
    }

    // Asıl güvenlik noktası: kafenin router'ı da 192.168.1.1 olabilir. İki
    // tarafta da ad okunabiliyorsa parmak izi kurtarmaz.
    @Test
    fun differentNameWithSameFingerprintDoesNotMatch() {
        val kayit = NetworkIdentity(ssid = "Ev", fingerprint = evFp)
        val simdi = NetworkIdentity(ssid = "Kafe", fingerprint = evFp)

        assertFalse(kayit.matches(simdi))
    }

    // Konum kapalıyken / arka planda ad okunamaz; o zaman tek dayanak parmak izi.
    @Test
    fun fallsBackToFingerprintWhenNameUnreadable() {
        val kayit = NetworkIdentity(ssid = "Ev", fingerprint = evFp)
        val simdi = NetworkIdentity(ssid = null, fingerprint = evFp)

        assertTrue(kayit.matches(simdi))
        assertFalse(kayit.matches(NetworkIdentity(ssid = null, fingerprint = otelFp)))
    }

    @Test
    fun matchingIsSymmetric() {
        val a = NetworkIdentity(ssid = null, fingerprint = evFp)
        val b = NetworkIdentity(ssid = "Ev", fingerprint = evFp)

        assertEquals(a.matches(b), b.matches(a))
        assertTrue(a.matches(b))
    }

    // Adsız ve parmak izsiz kimlik hiçbir şeyle eşleşmemeli; yoksa "boş kimlik"
    // her ağı güvenli yapardı.
    @Test
    fun emptyIdentityMatchesNothing() {
        val bos = NetworkIdentity(ssid = null, fingerprint = null)

        assertFalse(bos.usable)
        assertFalse(bos.matches(NetworkIdentity("Ev", evFp)))
        assertFalse(NetworkIdentity("Ev", evFp).matches(bos))
        assertFalse(bos.matches(bos))
    }

    @Test
    fun blankStringsCountAsMissing() {
        val bos = NetworkIdentity(ssid = "", fingerprint = "  ")

        assertFalse(bos.usable)
        assertFalse(NetworkIdentity("Ev", evFp).matches(bos))
    }

    @Test
    fun labelPrefersNameThenFingerprint() {
        assertEquals("Ev", NetworkIdentity("Ev", evFp).label())
        assertEquals(evFp, NetworkIdentity(null, evFp).label())
    }
}

class SubnetOfTest {
    private fun ip(vararg parts: Int) = ByteArray(parts.size) { parts[it].toByte() }

    @Test
    fun masksHostBits() {
        assertEquals("192.168.1.0/24", subnetOf(ip(192, 168, 1, 37), 24))
        assertEquals("10.0.0.0/8", subnetOf(ip(10, 3, 9, 200), 8))
        assertEquals("172.16.32.0/20", subnetOf(ip(172, 16, 47, 5), 20))
    }

    // Aynı /24'teki iki farklı cihaz IP'si aynı alt ağı vermeli, yoksa DHCP her
    // yenilemede ağı "yabancı" yapardı.
    @Test
    fun differentHostsInSameSubnetAgree() {
        assertEquals(subnetOf(ip(192, 168, 1, 5), 24), subnetOf(ip(192, 168, 1, 250), 24))
    }

    @Test
    fun rejectsNonIpv4AndBadPrefix() {
        assertNull(subnetOf(ByteArray(16), 64))
        assertNull(subnetOf(ip(192, 168, 1, 1), 33))
        assertNull(subnetOf(ip(192, 168, 1, 1), -1))
    }

    @Test
    fun handlesEdgePrefixes() {
        assertEquals("0.0.0.0/0", subnetOf(ip(192, 168, 1, 1), 0))
        assertEquals("192.168.1.1/32", subnetOf(ip(192, 168, 1, 1), 32))
    }
}
