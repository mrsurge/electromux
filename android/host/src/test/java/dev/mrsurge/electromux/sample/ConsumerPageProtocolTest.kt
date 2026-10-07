package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConsumerPageProtocolTest {
    private val page = "file:///android_asset/other.html"
    private val descriptor = ConsumerDescriptor("other.consumer", "Other", page,
        setOf("other.html"), mapOf(page to "other.html"), setOf("echo"), setOf("changed"))

    @Test fun unrelatedConsumerDispatchesDeclaredMethodAndEvents() {
        val protocol = ConsumerPageProtocol(descriptor, mapOf("echo" to { params ->
            ConsumerResult(params, listOf(ConsumerEvent("changed", JSONObject().put("ready", true))))
        }))
        val parsed = protocol.parse("""{"id":4,"method":"echo","params":{"value":"hello"}}""", page)
        val result = protocol.execute(parsed)
        assertEquals(4, result.getInt("id"))
        assertEquals("hello", result.getJSONObject("result").getJSONObject("value").getString("value"))
        assertEquals("changed", result.getJSONArray("events").getJSONObject(0).getString("name"))
        try { protocol.parse("""{"id":5,"method":"echo","params":{}}""", "https://example.com"); fail("remote authority") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun undeclaredEventsFailWithoutReplayingHandler() {
        var calls = 0
        val protocol = ConsumerPageProtocol(descriptor, mapOf("echo" to { _ ->
            calls++
            ConsumerResult(JSONObject(), listOf(ConsumerEvent("undeclared", JSONObject())))
        }))
        val result = protocol.execute(ConsumerPageProtocol.Request(1, "echo", JSONObject()))
        assertFalse(result.getJSONObject("result").getBoolean("ok"))
        assertEquals(1, calls)
    }
}
