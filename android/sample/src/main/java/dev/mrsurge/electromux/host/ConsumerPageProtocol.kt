package dev.mrsurge.electromux.host

import org.json.JSONObject
import org.json.JSONArray

data class ConsumerEvent(val name: String, val payload: JSONObject)
data class ConsumerResult(val value: JSONObject, val events: List<ConsumerEvent> = emptyList())

/** Platform-independent dispatch policy; consumer handlers own application semantics. */
class ConsumerPageProtocol(
    val descriptor: ConsumerDescriptor,
    handlers: Map<String, (JSONObject) -> ConsumerResult>,
) {
    private val handlers = handlers.toMap()
    init { require(this.handlers.keys == descriptor.methods) }

    data class Request(val id: Int, val method: String, val params: JSONObject)

    fun parse(raw: String, page: String?): Request {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..4096)
        val json = JSONObject(raw)
        val id = json.opt("id")
        val method = json.opt("method")
        val params = json.opt("params")
        require(id is Int && id > 0 && method is String && params is JSONObject)
        require(json.keys().asSequence().all { it in setOf("id", "method", "params") })
        require(descriptor.allows(page, method))
        return Request(id, method, params)
    }

    fun execute(request: Request): JSONObject {
        // Revalidate even if a consumer constructed Request directly.
        require(request.method in descriptor.methods)
        val events = JSONArray()
        val result = try {
            val outcome = handlers.getValue(request.method)(request.params)
            require(outcome.events.size <= 16 && outcome.events.all { it.name in descriptor.events })
            outcome.events.forEach { events.put(JSONObject().put("name", it.name).put("payload", it.payload)) }
            JSONObject().put("ok", true).put("value", outcome.value)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", JSONObject()
                .put("code", "CONSUMER_REQUEST_FAILED")
                .put("message", "Consumer request failed; no retry performed"))
        }
        val response = JSONObject().put("id", request.id).put("result", result).put("events", events)
        if (response.toString().toByteArray(Charsets.UTF_8).size <= 65536) return response
        return JSONObject().put("id", request.id).put("result", JSONObject()
            .put("ok", false).put("error", JSONObject().put("code", "REPLY_TOO_LARGE")
                .put("message", "Consumer reply too large; no retry performed")))
    }
}
