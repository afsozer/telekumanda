package com.agent.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsClientTest {
    @Test
    fun parsesCompatibleHealthProtocol() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { JSONObject().put("ok", true).put("protocolVersion", 2).put("protocolMinClient", 2) }
        val health = client.health(BridgeSettings("http://localhost", "token"))
        assertTrue(health.compatible)
        assertTrue(health.ok)
    }

    @Test
    fun parsesUnifiedOperationsAndEvents() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = {
            JSONObject().put("ok", true)
                .put("operations", JSONArray().put(JSONObject()
                    .put("id", "codex-app:s1").put("backend", "codex-app").put("backendLabel", "Codex App")
                    .put("sessionId", "s1").put("status", "waiting").put("needsAttention", true)))
                .put("events", JSONArray().put(JSONObject()
                    .put("id", "e1").put("kind", "attention").put("backend", "codex-app").put("backendLabel", "Codex App")))
                .put("counts", JSONObject().put("running", 0).put("waiting", 1).put("failed", 0))
        }

        val result = client.operations(BridgeSettings("http://localhost", "token"))

        assertEquals(1, result.operations.size)
        assertTrue(result.operations.first().needsAttention)
        assertEquals("attention", result.events.first().kind)
        assertEquals(1, result.counts.waiting)
    }

    @Test
    fun parsesProjectEnvelopeSessionsOutputsAndArtifacts() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req -> when {
            req.path == "/projects" -> JSONObject().put("ok", true).put("projects", JSONArray().put(JSONObject()
                .put("id", "p1").put("path", "C:/repo").put("name", "repo").put("displayName", "Repo")
                .put("sessionCount", 1).put("runningCount", 1).put("outputCount", 1)
                .put("providers", JSONArray().put("codex-app"))))
            req.path.startsWith("/projects/detail") -> JSONObject().put("ok", true)
                .put("project", JSONObject().put("id", "p1").put("path", "C:/repo").put("name", "repo").put("displayName", "Repo"))
                .put("sessions", JSONArray().put(JSONObject().put("backend", "codex-app").put("backendLabel", "Codex").put("sessionId", "s1").put("status", "running")))
                .put("outputs", JSONArray().put(JSONObject().put("name", "report.docx").put("path", "C:/repo/outputs/report.docx").put("size", 12)))
                .put("artifacts", JSONObject()
                    .put("changes", JSONArray().put(JSONObject().put("backend", "codex-app").put("path", "app.kt").put("status", "modified")))
                    .put("commands", JSONArray()).put("plan", JSONArray()))
                .put("security", JSONObject().put("profile", "safe")
                    .put("readRoots", JSONArray().put("C:/repo")).put("writeRoots", JSONArray().put("C:/repo")))
                .put("audit", JSONArray().put(JSONObject().put("id", "e1").put("action", "security_profile_applied")
                    .put("actor", "user").put("detail", JSONObject().put("profile", "safe")).put("at", "2026-07-11T12:00:00Z")))
            else -> JSONObject().put("ok", true)
        } }

        val projects = client.projects(BridgeSettings("http://localhost", "token"))
        val detail = client.projectDetail(BridgeSettings("http://localhost", "token"), "p1")

        assertEquals("Repo", projects.first().displayName)
        assertEquals("s1", detail.sessions.first().sessionId)
        assertEquals("report.docx", detail.outputs.first().name)
        assertEquals("app.kt", detail.changes.first().title)
        assertEquals("safe", detail.security.profile)
        assertEquals("C:/repo", detail.security.readRoots.first())
        assertEquals("security_profile_applied", detail.audit.first().action)
    }

    @Test
    fun appliesProjectSecurityOnlyThroughExplicitCall() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { JSONObject().put("ok", true) }

        client.applyProjectSecurity(BridgeSettings("http://localhost", "token"), "p1", "full")

        val request = client.recordedRequests.single()
        assertEquals("/projects/security/apply", request.path)
        assertEquals("p1", request.body?.optString("id"))
        assertEquals("full", request.body?.optString("profile"))
    }

    @Test
    fun pairsAndRotatesDeviceKeys() = runBlocking {
        val client = FakeBridgeClient()
        client.jsonResponseProvider = { req -> when (req.path) {
            "/pairing/start" -> JSONObject().put("ok", true).put("code", "123456").put("expiresAt", "soon")
            "/pairing/complete" -> JSONObject().put("ok", true).put("deviceId", "d1").put("key", "abk_first")
            "/devices/rotate" -> JSONObject().put("ok", true).put("deviceId", "d1").put("key", "abk_second")
            else -> JSONObject().put("ok", false)
        } }
        val settings = BridgeSettings("http://localhost", "legacy")
        assertEquals("123456", client.startDevicePairing(settings).code)
        assertEquals("abk_first", client.completeDevicePairing(settings, "123456", "phone").key)
        assertEquals("abk_second", client.rotateDeviceKey(settings.copy(token = "abk_first")).key)
        assertEquals("123456", client.recordedRequests[1].body?.optString("code"))
    }
}
