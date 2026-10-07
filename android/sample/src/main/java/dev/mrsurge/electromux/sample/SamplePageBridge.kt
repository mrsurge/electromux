package dev.mrsurge.electromux.sample

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cefrium.CefriumBrowser
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import dev.mrsurge.electromux.host.RendererEventGate
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class SamplePageBridge(context: Context, private val evaluate: (String) -> Unit) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    private val gate = RendererEventGate(SampleConsumer.descriptor.routes.keys)
    private val pendingEvents = AtomicInteger()
    private val client = HelperClient(context, SampleConsumer.descriptor.events, ::receiveEvent)
    private val protocol = SampleConsumer.protocol(client)
    @Volatile private var closed = false

    fun beginNavigation() {
        gate.beginNavigation()
        // Keep disconnect ordered after an already-running request. It cannot
        // kill the retained backend; new document requests enter after this.
        try { io.execute { client.disconnect() } }
        catch (_: RejectedExecutionException) { client.disconnect() }
    }

    fun changePage(page: String?) { gate.changePage(page) }

    private fun receiveEvent(name: String, frame: JSONObject) {
        val ticket = gate.captureEvent() ?: return
        if (name !in protocol.descriptor.events) return
        val payload = frame.optJSONObject("data") ?: return
        val envelope = JSONObject().put("documentId", ticket.documentId)
            .put("name", name).put("payload", payload).toString()
        if (envelope.toByteArray(Charsets.UTF_8).size > 65536) return
        if (pendingEvents.incrementAndGet() > 16) {
            pendingEvents.decrementAndGet()
            client.disconnect() // Bound the UI queue too; never stop the backend.
            return
        }
        main.post {
            try {
                if (!closed && gate.accepts(ticket)) {
                    // JSON is passed as a quoted string, never spliced as executable code.
                    evaluate("window.__electromuxReceiveEvent?.(${JSONObject.quote(envelope)})")
                }
            } finally { pendingEvents.decrementAndGet() }
        }
    }

    fun handle(request: String, origin: String?, callback: CefriumBrowser.QueryCallback): Boolean {
        val generation = gate.captureRequest(origin)
        if (closed || generation == null || request.toByteArray().size > 4096) {
            callback.failure(403, "Untrusted or oversized sample request")
            return true
        }
        val parsed = try {
            protocol.parse(request, origin)
        } catch (_: Exception) {
            callback.failure(400, "Invalid sample request")
            return true
        }
        try {
            io.execute {
                if (!gate.current(generation, origin!!)) return@execute
                try { gate.bind(generation, origin, parsed.documentId) }
                catch (_: Exception) {
                    main.post { if (gate.current(generation, origin)) callback.failure(403, "Renderer document identity rejected") }
                    return@execute
                }
                val response = protocol.execute(parsed)
                main.post { if (!closed && gate.current(generation, origin)) callback.success(response.toString()) }
            }
        } catch (_: RejectedExecutionException) {
            callback.failure(429, "Sample I/O queue is full or closed")
        }
        return true
    }

    override fun close() {
        closed = true
        gate.close()
        client.close()
        io.shutdownNow()
    }
}
