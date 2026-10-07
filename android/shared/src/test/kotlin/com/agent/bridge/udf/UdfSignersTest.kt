package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Test

class UdfSignersTest {

    @Test
    fun `imzasiz belge bos liste dondurur`() {
        assertEquals(emptyList<String>(), udfSignerNames(null))
        assertEquals(emptyList<String>(), udfSignerNames(ByteArray(0)))
    }

    @Test
    fun `bozuk imza blogu patlamaz`() {
        // Belgenin okunması imza bloğunun okunabilirliğine bağlı olmamalı:
        // ayrıştırılamayan sign.sgn yalnızca "imzalayan bilinmiyor" demektir.
        assertEquals(emptyList<String>(), udfSignerNames(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(emptyList<String>(), udfSignerNames("bu bir sertifika değil".toByteArray()))
    }
}
