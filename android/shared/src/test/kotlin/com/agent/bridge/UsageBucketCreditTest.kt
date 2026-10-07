package com.agent.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ön ödemeli bakiye çubuğu: köprü 10 $ = dolu ölçeğiyle `creditUsd` gönderir;
 * 10 $ üstü "bol" sayılır ve ayrı renkle çizilir. Kota kovalarında alan yoktur.
 */
class UsageBucketCreditTest {
    private fun bucket(creditUsd: Double?) = UsageBucket(
        id = "nanogpt-balance", label = "Kalan bakiye", window = "", description = "",
        remainingFraction = 1.0, resetTime = "", value = "$12.00", creditUsd = creditUsd,
    )

    @Test
    fun onDolarUstuBol() {
        assertTrue(bucket(10.01).creditAbundant())
        assertTrue(bucket(25.0).creditAbundant())
    }

    @Test
    fun onDolarVeAltiBolDegil() {
        assertFalse(bucket(10.0).creditAbundant())
        assertFalse(bucket(3.57).creditAbundant())
        assertFalse(bucket(0.0).creditAbundant())
    }

    @Test
    fun kotaKovasindaAlanYokBolDegil() {
        assertFalse(bucket(null).creditAbundant())
    }

    @Test
    fun jsonAlaniOkunur() {
        val json = JSONObject("""{"id":"deepseek-balance","label":"Kalan bakiye","remainingFraction":1,"metered":true,"creditUsd":11.41}""")
        val creditUsd = if (json.isNull("creditUsd")) null else json.optDouble("creditUsd").takeIf { it.isFinite() }
        assertEquals(11.41, creditUsd!!, 1e-9)
        val eski = JSONObject("""{"id":"claude-5h","remainingFraction":0.2}""")
        assertNull(if (eski.isNull("creditUsd")) null else eski.optDouble("creditUsd"))
    }
}
