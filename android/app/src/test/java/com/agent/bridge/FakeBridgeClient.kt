package com.agent.bridge

import org.json.JSONObject
import okhttp3.Request

open class FakeBridgeClient : BridgeClient() {
    data class FakeRequest(
        val method: String,
        val path: String,
        val body: JSONObject? = null
    )

    val recordedRequests = mutableListOf<FakeRequest>()
    
    var jsonResponseProvider: (FakeRequest) -> JSONObject = { 
        JSONObject().put("ok", true) 
    }

    override suspend fun getJson(settings: BridgeSettings, path: String): JSONObject {
        val req = FakeRequest("GET", path)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun getJsonLongPoll(settings: BridgeSettings, path: String): JSONObject {
        val req = FakeRequest("GET_LONG_POLL", path)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun postJson(settings: BridgeSettings, path: String, body: JSONObject): JSONObject {
        val req = FakeRequest("POST", path, body)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun getJsonWorker(settings: BridgeSettings, path: String): JSONObject {
        val req = FakeRequest("GET_WORKER", path)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun postJsonWorker(settings: BridgeSettings, path: String, body: JSONObject): JSONObject {
        val req = FakeRequest("POST_WORKER", path, body)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun executeJson(request: Request): JSONObject {
        val path = request.url.encodedPath
        val method = request.method
        val req = FakeRequest(method, path)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun executeJsonLongPoll(request: Request): JSONObject {
        val path = request.url.encodedPath
        val method = request.method
        val req = FakeRequest(method, path)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }

    override suspend fun executeJsonWorker(request: Request): JSONObject {
        val path = request.url.encodedPath
        val method = request.method
        val req = FakeRequest(method, path)
        recordedRequests.add(req)
        return jsonResponseProvider(req)
    }
}
