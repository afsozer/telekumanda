package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OpencodeAgentParsingTest {
    @Test
    fun v2OturumuBirlesikGecmisteGorunur() {
        val state = RemoteUiState(opencode = OpencodeUiState(diskSessions = listOf(
            AppDiskSession("ses_2", "C:/work", "V2 oturumu", "", 1, 1790450580000L)
        )))

        assertEquals(listOf("ses_2"), state.backendDiskSessions("opencode2-app").map { it.id })
    }

    @Test
    fun v2OturumTarihiGecmisteDogruSiralanir() = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                assertEquals("/opencode2-app/disk-sessions", request.path)
                JSONObject().put("sessions", JSONArray().put(
                    JSONObject().put("id", "ses_1").put("title", "Son oturum")
                        .put("updated", 1790450580000L)
                ))
            }
        }

        val sessions = client.opencodeAppDiskSessions(BridgeSettings("http://localhost", ""), "opencode2-app")

        assertEquals(1790450580000L, sessions.single().mtime)
    }

    @Test
    fun v2KimligiAjanAdiOlurAltAjanSecilemez() = runBlocking {
        val client = FakeBridgeClient().apply {
            jsonResponseProvider = { request ->
                assertEquals("/opencode2-app/agents", request.path)
                JSONObject().put("agents", JSONArray().apply {
                    put(JSONObject().put("id", "build").put("label", "Build").put("mode", "primary"))
                    put(JSONObject().put("id", "general").put("label", "General").put("mode", "subagent"))
                })
            }
        }

        val agents = client.opencodeAppAgents(BridgeSettings("http://localhost", ""), "opencode2-app")

        assertEquals(listOf("build"), agents.map { it.name })
    }
}
