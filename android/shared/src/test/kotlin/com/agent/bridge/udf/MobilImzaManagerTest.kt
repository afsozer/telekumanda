package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilImzaManagerTest {

    // --- getOperatorId ---

    @Test
    fun testGetOperatorIdKnownOperators() {
        assertEquals(1, MobilImzaManager.getOperatorId("TURKCELL"))
        assertEquals(2, MobilImzaManager.getOperatorId("AVEA"))
        assertEquals(2, MobilImzaManager.getOperatorId("TURKTELEKOM"))
        assertEquals(2, MobilImzaManager.getOperatorId("TURK TELEKOM"))
        assertEquals(3, MobilImzaManager.getOperatorId("VODAFONE"))
    }

    @Test
    fun testGetOperatorIdCaseInsensitive() {
        // UI büyük harf gönderir ama dayanıklılık için küçük/karışık harf de kabul edilmeli.
        assertEquals(1, MobilImzaManager.getOperatorId("turkcell"))
        assertEquals(1, MobilImzaManager.getOperatorId("Turkcell"))
        assertEquals(3, MobilImzaManager.getOperatorId("Vodafone"))
    }

    @Test
    fun testGetOperatorIdUnknownThrows() {
        // Bilinmeyen operatör artık sessizce TURKCELL'e çaklanmamalı; yanlış imza
        // denemesini önlemek için exception fırlatmalı.
        assertThrows(IllegalArgumentException::class.java) {
            MobilImzaManager.getOperatorId("BILINMEYEN")
        }
    }

    @Test
    fun testGetOperatorIdEmptyThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            MobilImzaManager.getOperatorId("")
        }
    }

    // --- normalizePhoneNumber ---

    @Test
    fun testNormalizePhoneNumberWithCountryCode() {
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("+905321234567"))
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("905321234567"))
    }

    @Test
    fun testNormalizePhoneNumberWithLeadingZero() {
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("05321234567"))
    }

    @Test
    fun testNormalizePhoneNumberWithoutLeadingZero() {
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("5321234567"))
    }

    @Test
    fun testNormalizePhoneNumberWithSeparators() {
        // UI'dan boşluk, tire, parantez ile gelebilir.
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("0532 123 45 67"))
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("0532-123-45-67"))
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("(0532) 123 45 67"))
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("+90 (532) 123-4567"))
    }

    @Test
    fun testNormalizePhoneNumberWithWhitespace() {
        assertEquals("05321234567", MobilImzaManager.normalizePhoneNumber("  05321234567  "))
    }

    @Test
    fun testNormalizePhoneNumberInvalidTooShort() {
        assertNull(MobilImzaManager.normalizePhoneNumber("532123456"))
        assertNull(MobilImzaManager.normalizePhoneNumber("532"))
    }

    @Test
    fun testNormalizePhoneNumberInvalidContainsLetters() {
        assertNull(MobilImzaManager.normalizePhoneNumber("05321a34567"))
        assertNull(MobilImzaManager.normalizePhoneNumber("0532abc4567"))
    }

    @Test
    fun testNormalizePhoneNumberInvalidDoesNotStartWith5AfterPrefix() {
        // 0 ile başlayan ama 2. hanesi 5 değilse (sabit hat) mobil imza için geçersiz.
        assertNull(MobilImzaManager.normalizePhoneNumber("02121234567"))
        // +90 prefix'i çıktıktan sonra 2 ile başlıyor.
        assertNull(MobilImzaManager.normalizePhoneNumber("+902121234567"))
    }

    @Test
    fun testNormalizePhoneNumberInvalidTooLong() {
        assertNull(MobilImzaManager.normalizePhoneNumber("053212345678"))
        assertNull(MobilImzaManager.normalizePhoneNumber("+9053212345678"))
    }

    @Test
    fun testNormalizePhoneNumberEmpty() {
        assertNull(MobilImzaManager.normalizePhoneNumber(""))
        assertNull(MobilImzaManager.normalizePhoneNumber("   "))
    }

    // --- resultCode sabitleri (gerileme koruması) ---
    // Bu değerler ağ üzerinden de gönderildiği için (/gsmOperator alanı hariç) değişirse
    // sunucu etkileşimi bozulur. Sabit değerlerini kilitlemek önemlidir.

    @Test
    fun testResultCodeConstantsAreStable() {
        assertEquals(0, MobilImzaManager.RESULT_SUCCESS)
        assertEquals(-1, MobilImzaManager.RESULT_PENDING)
        assertEquals(9999, MobilImzaManager.RESULT_WAITING)
        assertEquals(-2, MobilImzaManager.RESULT_ERROR_NETWORK)
    }

    @Test
    fun testGetSignatureEnvelopeUsesWsdlElementOrder() {
        val envelope = MobilImzaManager.buildGetSignatureEnvelope(
            dataToBeDisplayed = "Evrak & Imzalama",
            apTransId = "TRANS<123>"
        )

        val dataIndex = envelope.indexOf("<cli:dataToBeDisplayed>")
        val transactionIndex = envelope.indexOf("<cli:apTransId>")

        assertTrue(dataIndex >= 0)
        assertTrue(transactionIndex > dataIndex)
        assertTrue(envelope.contains("Evrak &amp; Imzalama"))
        assertTrue(envelope.contains("TRANS&lt;123&gt;"))
        assertEquals(-1, envelope.indexOf("<cli:gsmOperator>"))
    }

    @Test
    fun testGetHashEnvelopeEscapesUserControlledValues() {
        val envelope = MobilImzaManager.buildGetHashEnvelope(
            documentBase64 = "abc+/=",
            telNo = "05<32>&123456",
            operatorId = 1
        )

        assertTrue(envelope.contains("<cli:dataToBeSigned>abc+/=</cli:dataToBeSigned>"))
        assertTrue(envelope.contains("<cli:telNo>05&lt;32&gt;&amp;123456</cli:telNo>"))
        assertTrue(envelope.contains("<cli:gsmOperator>1</cli:gsmOperator>"))
    }
}
