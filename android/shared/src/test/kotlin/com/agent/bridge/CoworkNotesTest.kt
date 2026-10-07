package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkNotesTest {
    @Test
    fun `AI onizlemesi uzun istek ucunu kullanir ve yalniz onay ayri istekte kaydeder`() = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path == "/cowork/note/ai") {
                    JSONObject(
                        """
                        {
                          "ok":true,
                          "noteId":"_genel-notlar/deneme",
                          "action":"ozetle",
                          "title":"Deneme",
                          "mdPath":"C:\\CoworkSpaces\\_genel-notlar\\deneme.md",
                          "original":"Asıl metin",
                          "proposed":"Asıl metin\n\n## AI Özeti\n\nKısa özet",
                          "base_hash":"abc123",
                          "provider":"claude-app"
                        }
                        """.trimIndent(),
                    )
                } else {
                    JSONObject().put("ok", true)
                }
            }
        }
        val settings = BridgeSettings("http://localhost", "token")

        val preview = client.coworkNoteAi(settings, "_genel-notlar/deneme", "ozetle")

        assertEquals("POST_AI", client.recordedRequests.single().method)
        assertEquals("/cowork/note/ai", client.recordedRequests.single().path)
        assertEquals("ozetle", client.recordedRequests.single().body?.getString("action"))
        assertTrue(preview.proposed.contains("## AI Özeti"))

        client.coworkApplyNoteAi(settings, preview)

        val apply = client.recordedRequests.last()
        assertEquals("POST", apply.method)
        assertEquals("/cowork/note/ai/apply", apply.path)
        assertEquals("abc123", apply.body?.getString("base_hash"))
        assertEquals(preview.proposed, apply.body?.getString("proposed"))
    }

    @Test
    fun `not yeniden adlandirma ve silme dogru uclari kullanir`() = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                if (request.path.endsWith("/rename")) {
                    JSONObject(
                        """
                        {"ok":true,"note":{"id":"_genel-notlar/yeni","title":"Yeni",
                        "mdPath":"C:\\Cowork\\_genel-notlar\\yeni.md"}}
                        """.trimIndent(),
                    )
                } else {
                    JSONObject().put("ok", true)
                }
            }
        }
        val settings = BridgeSettings("http://localhost", "token")

        val renamed = client.coworkRenameNote(settings, "_genel-notlar/eski", "Yeni")
        client.coworkDeleteNote(settings, renamed.id)

        assertEquals("Yeni", renamed.title)
        assertEquals("/cowork/note/rename", client.recordedRequests[0].path)
        assertEquals("_genel-notlar/eski", client.recordedRequests[0].body?.getString("noteId"))
        assertEquals("Yeni", client.recordedRequests[0].body?.getString("name"))
        assertEquals("/cowork/note/delete", client.recordedRequests[1].path)
        assertEquals("_genel-notlar/yeni", client.recordedRequests[1].body?.getString("noteId"))
    }

    @Test
    fun `JSON null mdPath null olarak cozulur`() {
        val note = parseCoworkNote(
            JSONObject(
                """
                {
                  "id": "_genel-notlar/bos",
                  "title": "Boş",
                  "project": null,
                  "mdPath": null
                }
                """.trimIndent(),
            ),
        )

        assertNull(note.project)
        assertNull(note.mdPath)
    }

    @Test
    fun `metin notu tum alanlariyla cozulur`() {
        val note = parseCoworkNote(
            JSONObject(
                """
                {
                  "id":"proje-a/notlar/durusma",
                  "title":"Duruşma",
                  "project":{"name":"proje-a","path":"C:\\CoworkSpaces\\proje-a"},
                  "mdPath":"C:\\CoworkSpaces\\proje-a\\notlar\\durusma.md",
                  "updated_at_ms":1234
                }
                """.trimIndent()
            )
        )

        assertEquals("proje-a/notlar/durusma", note.id)
        assertEquals("Duruşma", note.title)
        assertEquals("proje-a", note.project?.name)
        assertEquals("C:\\CoworkSpaces\\proje-a", note.project?.path)
        assertEquals("C:\\CoworkSpaces\\proje-a\\notlar\\durusma.md", note.mdPath)
        assertEquals(1234, note.updatedAtMs)
    }
}
