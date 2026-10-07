package com.agent.bridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Köprü hatanın SEBEBİNİ gövdede yazıyor ama istemci gövdeyi atıp yalnız
// "HTTP <kod>" fırlatıyordu. Bu tuzak iki kez ısırdı: cowork kilidi (409) ve
// Codex hedefi (400 — "thread henüz başlamadı", kullanıcı yalnız kodu gördü).
class BridgeHttpErrorTest {

    private fun response(code: Int, body: String, json: Boolean = true): Response =
        Response.Builder()
            .request(Request.Builder().url("http://localhost/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody((if (json) "application/json" else "text/html").toMediaType()))
            .build()

    @Test
    fun hataGovdesindekiAciklamaKullaniciyaTasinir() {
        val error = runCatching {
            response(400, """{"ok":false,"error":"thread henüz başlamadı — önce bir mesaj gönder"}""")
                .readJsonOrThrow()
        }.exceptionOrNull()

        assertTrue(error is BridgeHttpException)
        assertEquals(400, (error as BridgeHttpException).code)
        assertEquals("thread henüz başlamadı — önce bir mesaj gönder", error.message)
    }

    // Kod SAYISAL okunabilmeli: "HTTP 409" dizge karşılaştırması kırılgandı ve
    // gövde okunmaya başlayınca sessizce bozulurdu (not çakışması tespiti).
    @Test
    fun kodSayisalOkunur() {
        val error = runCatching {
            response(409, """{"error":"not degisti"}""").readJsonOrThrow()
        }.exceptionOrNull() as BridgeHttpException
        assertEquals(409, error.code)
    }

    @Test
    fun govdeBosOlunca_koda_duser() {
        val error = runCatching { response(500, "").readJsonOrThrow() }.exceptionOrNull()
        assertEquals("HTTP 500", error?.message)
    }

    // JSON olmayan gövdede (proxy/HTML hata sayfası) ilk satır koddan iyidir.
    @Test
    fun jsonOlmayanGovdedeIlkSatirGosterilir() {
        val error = runCatching {
            response(502, "Bad Gateway\nnginx", json = false).readJsonOrThrow()
        }.exceptionOrNull()
        assertEquals("Bad Gateway", error?.message)
    }

    @Test
    fun basariliCevapAynenParseEdilir() {
        val json = response(200, """{"ok":true,"goal":{"objective":"x"}}""").readJsonOrThrow()
        assertTrue(json.optBoolean("ok"))
        assertEquals("x", json.optJSONObject("goal")?.optString("objective"))
    }

    @Test
    fun basariliBosGovdeBosNesneDoner() {
        assertEquals(0, response(200, "").readJsonOrThrow().length())
    }
}
