package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebugHealerTest {
    private class FakeAccess(
        private val wifi: Boolean,
        initialEnabled: Boolean,
        private val safe: Boolean = true,
        private val acceptWrite: Boolean = true,
        private val reflectWrite: Boolean = true,
    ) : WirelessDebugAccess {
        private var enabled = initialEnabled
        var writes = 0
            private set
        var reads = 0
            private set

        override fun hasWifi(): Boolean = wifi
        override fun isNetworkSafe(): Boolean = safe

        override fun isEnabled(): Boolean {
            reads += 1
            return enabled
        }

        override fun enable(): Boolean {
            writes += 1
            if (acceptWrite && reflectWrite) enabled = true
            return acceptWrite
        }
    }

    @Test
    fun noWifiDoesNotWrite() {
        val access = FakeAccess(wifi = false, initialEnabled = false)

        assertEquals(WirelessDebugHealOutcome.NO_WIFI, runWirelessDebugHeal(access))
        assertEquals(0, access.writes)
    }

    // Asıl davranış değişikliği (22.08.2026): liste dışındaki ağda ayara ne
    // yazılır ne de okunur, dolayısıyla sistem izin sormaz.
    @Test
    fun unsafeNetworkTouchesNothing() {
        val access = FakeAccess(wifi = true, initialEnabled = false, safe = false)

        assertEquals(WirelessDebugHealOutcome.UNSAFE_NETWORK, runWirelessDebugHeal(access))
        assertEquals(0, access.writes)
        assertEquals(0, access.reads)
    }

    // Kullanıcı orada kablosuz hata ayıklamayı kendi açtıysa da karışmıyoruz.
    @Test
    fun unsafeNetworkLeavesAnAlreadyEnabledSettingAlone() {
        val access = FakeAccess(wifi = true, initialEnabled = true, safe = false)

        assertEquals(WirelessDebugHealOutcome.UNSAFE_NETWORK, runWirelessDebugHeal(access))
        assertEquals(0, access.writes)
        assertTrue(access.isEnabled())
    }

    @Test
    fun alreadyEnabledDoesNotWrite() {
        val access = FakeAccess(wifi = true, initialEnabled = true)

        assertEquals(WirelessDebugHealOutcome.ALREADY_ENABLED, runWirelessDebugHeal(access))
        assertEquals(0, access.writes)
    }

    @Test
    fun disabledSettingIsEnabledAndReadBack() {
        val access = FakeAccess(wifi = true, initialEnabled = false)

        assertEquals(WirelessDebugHealOutcome.ENABLED, runWirelessDebugHeal(access))
        assertEquals(1, access.writes)
        assertTrue(access.isEnabled())
    }

    @Test
    fun rejectedWriteIsReported() {
        val access = FakeAccess(wifi = true, initialEnabled = false, acceptWrite = false)

        assertEquals(WirelessDebugHealOutcome.WRITE_REJECTED, runWirelessDebugHeal(access))
        assertEquals(1, access.writes)
        assertFalse(access.isEnabled())
    }

    @Test
    fun successfulReturnWithoutChangedSettingIsRejected() {
        val access = FakeAccess(
            wifi = true,
            initialEnabled = false,
            acceptWrite = true,
            reflectWrite = false,
        )

        assertEquals(WirelessDebugHealOutcome.WRITE_REJECTED, runWirelessDebugHeal(access))
        assertEquals(1, access.writes)
        assertFalse(access.isEnabled())
    }
}

class HealBackoffTest {
    private val ev = "Ev"
    private val kafe = "Kafe"

    @Test
    fun singleEnableKeepsHealingAllowed() {
        val backoff = HealBackoff()

        backoff.record(ev, WirelessDebugHealOutcome.ENABLED, 0L)

        assertTrue(backoff.allowed(ev, 1_000L))
    }

    // "Hayır" imzası: aynı ağda arka arkaya açılmak zorunda kalmak.
    @Test
    fun threeConsecutiveEnablesMuteTheSameNetwork() {
        val backoff = HealBackoff()

        repeat(3) { i -> backoff.record(ev, WirelessDebugHealOutcome.ENABLED, i * 60_000L) }

        assertFalse(backoff.allowed(ev, 3 * 60_000L))
    }

    @Test
    fun muteExpiresAfterCooldown() {
        val backoff = HealBackoff(limit = 3, cooldownMs = 30 * 60_000L)

        repeat(3) { backoff.record(ev, WirelessDebugHealOutcome.ENABLED, 0L) }

        assertFalse(backoff.allowed(ev, 29 * 60_000L))
        assertTrue(backoff.allowed(ev, 30 * 60_000L))
    }

    // Kullanıcı "İzin ver" derse bir sonraki tur ALREADY_ENABLED olur; sayaç sıfırlanmalı.
    @Test
    fun acceptedPromptResetsStrikes() {
        val backoff = HealBackoff()

        backoff.record(ev, WirelessDebugHealOutcome.ENABLED, 0L)
        backoff.record(ev, WirelessDebugHealOutcome.ENABLED, 60_000L)
        backoff.record(ev, WirelessDebugHealOutcome.ALREADY_ENABLED, 120_000L)
        backoff.record(ev, WirelessDebugHealOutcome.ENABLED, 180_000L)

        assertTrue(backoff.allowed(ev, 180_000L))
    }

    // Healer'ın asıl derdi: ağ değişince ayar kapanır. Kimlik değiştiği için bu
    // sessizleştirmeye takılmamalı.
    @Test
    fun networkChangeResetsEverything() {
        val backoff = HealBackoff()

        repeat(3) { backoff.record(ev, WirelessDebugHealOutcome.ENABLED, 0L) }
        assertFalse(backoff.allowed(ev, 0L))

        assertTrue(backoff.allowed(kafe, 0L))
        backoff.record(kafe, WirelessDebugHealOutcome.ENABLED, 0L)
        assertTrue(backoff.allowed(kafe, 0L))
    }

    // Yazma reddediliyorsa dakikada bir denemenin karşılığı yok.
    @Test
    fun repeatedWriteRejectionAlsoMutes() {
        val backoff = HealBackoff()

        repeat(3) { backoff.record(ev, WirelessDebugHealOutcome.WRITE_REJECTED, 0L) }

        assertFalse(backoff.allowed(ev, 0L))
    }

    // Güvenli olmayan ağda hiçbir şey yapılmıyor; sayaç da işlememeli.
    @Test
    fun unsafeNetworkNeverMutes() {
        val backoff = HealBackoff()

        repeat(10) { backoff.record(ev, WirelessDebugHealOutcome.UNSAFE_NETWORK, 0L) }

        assertTrue(backoff.allowed(ev, 0L))
    }
}
