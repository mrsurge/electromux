package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.ConsumerDescriptor
import dev.mrsurge.electromux.host.ConsumerPageProtocol
import dev.mrsurge.electromux.host.ConsumerResult
import dev.mrsurge.electromux.host.ConsumerEvent
import org.json.JSONObject

object SampleConsumer {
    val descriptor = ConsumerDescriptor(
        "electromux.sample", "Electromux Sample", "file:///android_asset/index.html",
        setOf("index.html", "sample.js", "electromux-bridge.js"),
        mapOf("file:///android_asset/index.html" to "index.html"),
        setOf("connect", "start", "ping", "status", "detach", "stop", "shutdown"),
        setOf("sample.updated", "sample.state"),
    )

    fun protocol(client: HelperClient) = ConsumerPageProtocol(descriptor,
        descriptor.methods.associateWith { method ->
            { params: JSONObject ->
                val keys = params.keys().asSequence().toSet()
                require(keys == if (method == "ping") setOf("value") else emptySet<String>())
                val value = if (method == "ping") params.get("value") else ""
                require(value is String && value.length <= 256)
                val result = if (method == "connect") client.connect() else client.call(method, value)
                val notifications = result.optJSONObject("result")?.optJSONArray("events")
                val events = if (notifications == null) emptyList() else
                    (0 until notifications.length()).map { index ->
                        val event = notifications.getJSONObject(index)
                        ConsumerEvent(event.getString("event"), event)
                    }
                ConsumerResult(result, events)
            }
        })
}
