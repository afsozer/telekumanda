package com.agent.bridge.widget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class KotaWidgetDataTest {
    // 05.10.2026'da köprüden alınan gerçek yanıtın biçimi (değerler aynı).
    private val response = """
        {"groups":[
          {"name":"Claude Code","source":"claude","stale":false,
           "description":"Abonelik: Max 5x • Tier: default_claude_max_5x • Resmi kalan limit (claude /usage ile aynı kaynak).",
           "buckets":[
             {"id":"claude-5h","label":"Son 5 saat","value":"%93 kaldı","remainingFraction":0.93,"resetTime":"2026-10-05T14:59:59.925739+00:00"},
             {"id":"claude-week","label":"Son 7 gün","value":"%50 kaldı","remainingFraction":0.5,"resetTime":"2026-10-09T05:00:00.925765+00:00"},
             {"id":"claude-week-fable","label":"7 gün (Fable)","value":"%98 kaldı","remainingFraction":0.98,"resetTime":"2026-10-09T04:59:59.925988+00:00"},
             {"id":"claude-credit","label":"Kredi","value":"$0 / $50","remainingFraction":1,"resetTime":""}
           ]},
          {"name":"Codex","source":"codex","buckets":[{"id":"codex-primary","label":"Son 5 saat","remainingFraction":1}]},
          {"name":"Nano-GPT","source":"nanogpt","description":"Resmi Nano-GPT API bakiyesi.",
           "buckets":[{"id":"nanogpt-balance","label":"Kalan bakiye","value":"$3.74","remainingFraction":0.3736,
             "metered":true,"creditUsd":3.74,"todaySpendUsd":0.0469,"todayRequests":39}]}
        ],"note":"","updatedAt":"2026-10-05T10:18:16.000Z"}
    """.trimIndent()

    private val received = Instant.parse("2026-10-05T10:20:00Z")

    @Test
    fun `claude and nano cards are extracted, credit row stays out`() {
        val s = KotaWidgetData.parse(JSONObject(response), received)
        val claude = s.claude!!
        assertEquals("Max 5x", claude.plan)
        assertEquals("claude-5h", claude.hero?.id)
        assertEquals(93.0, claude.hero!!.remaining, 0.001)
        assertEquals(Instant.parse("2026-10-05T14:59:59.925739Z"), claude.hero.resetAt)
        assertEquals(listOf("claude-week", "claude-week-fable"), claude.rest.map { it.id })
        val nano = s.nano!!
        assertEquals(3.74, nano.usd, 0.0001)
        assertEquals(0.0469, nano.todaySpendUsd!!, 0.00001)
        assertEquals(39, nano.todayRequests)
        assertEquals(Instant.parse("2026-10-05T10:18:16Z"), s.measuredAt)
    }

    @Test
    fun `old bridge without today fields or updatedAt still renders`() {
        val json = JSONObject(
            """{"groups":[{"source":"nanogpt","buckets":[{"id":"nanogpt-balance","value":"$12.50","creditUsd":12.5}]}]}""",
        )
        val s = KotaWidgetData.parse(json, received)
        assertNull(s.claude)
        val nano = s.nano!!
        assertNull(nano.todaySpendUsd)
        assertNull(nano.todayRequests)
        assertEquals(received, s.measuredAt)
        assertTrue(KotaWidgetData.nanoOverflow(nano))
        assertEquals(KotaTone.OVERFLOW, KotaWidgetData.nanoTone(nano))
        assertEquals("10 $ ölçeğin üstünde", KotaWidgetData.nanoBottomLine(nano))
    }

    @Test
    fun `local token summary is not drawn as a quota`() {
        val json = JSONObject(
            """{"groups":[{"source":"claude","buckets":[
                {"id":"claude-local-5h","label":"Son 5 saat","value":"12 token","remainingFraction":1}]}]}""",
        )
        val claude = KotaWidgetData.parse(json, received).claude!!
        assertNull(claude.hero)
        assertTrue(claude.rest.isEmpty())
    }

    @Test
    fun `thresholds match the Mac card`() {
        assertEquals(KotaTone.ACCENT, KotaWidgetData.remainingTone(25.0))
        assertEquals(KotaTone.ORANGE, KotaWidgetData.remainingTone(24.9))
        assertEquals(KotaTone.RED, KotaWidgetData.remainingTone(9.9))
        assertEquals(KotaTone.ORANGE, KotaWidgetData.nanoTone(KotaNano(2.0, null, null)))
        assertEquals(KotaTone.RED, KotaWidgetData.nanoTone(KotaNano(0.5, null, null)))
        assertEquals(KotaTone.ACCENT, KotaWidgetData.nanoTone(KotaNano(3.74, null, null)))
    }

    @Test
    fun `labels and money are formatted like the Mac card`() {
        assertEquals("$3,74", KotaWidgetData.usdLabel(3.736))
        assertEquals("$124", KotaWidgetData.usdLabel(123.6))
        assertEquals("bugün $0,05 · 39 istek", KotaWidgetData.nanoBottomLine(KotaNano(3.74, 0.0469, 39)))
        assertEquals("Fable", KotaWidgetData.compactLabel("7 gün (Fable)"))
        assertEquals("Fable", KotaWidgetData.compactLabel("7 gün · Fable"))
        assertEquals("7 gün", KotaWidgetData.compactLabel("Son 7 gün"))
    }

    @Test
    fun `reset is shown as a clock time, with weekday beyond a day`() {
        val zone = ZoneId.of("Europe/Istanbul")
        val now = Instant.parse("2026-10-05T10:20:00Z")
        assertEquals("17:59", KotaWidgetData.resetLabel(Instant.parse("2026-10-05T14:59:59Z"), now, zone))
        assertEquals("Cum 08:00", KotaWidgetData.resetLabel(Instant.parse("2026-10-09T05:00:00Z"), now, zone))
        assertEquals("yenilendi", KotaWidgetData.resetLabel(Instant.parse("2026-10-05T10:00:00Z"), now, zone))
    }

    @Test
    fun `stale after thirty minutes`() {
        val s = KotaSnapshot(null, null, Instant.parse("2026-10-05T10:00:00Z"))
        assertFalse(KotaWidgetData.isStale(s, Instant.parse("2026-10-05T10:29:00Z")))
        assertTrue(KotaWidgetData.isStale(s, Instant.parse("2026-10-05T10:31:00Z")))
    }
}
