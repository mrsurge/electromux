package dev.mrsurge.electromux.sample

import android.os.Handler
import android.os.Looper
import com.cefrium.CefriumBrowser
import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import dev.mrsurge.electromux.host.RendererEventGate
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class SamplePageBridge(private val service: SampleRuntimeService, private val evaluate: (String) -> Unit) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val gate = RendererEventGate(SampleConsumer.descriptor.routes.keys)
    private val pendingEvents = AtomicInteger()
    private val protocol = service.protocol
    private var subscription: Closeable? = null
    @Volatile private var closed = false

    @Synchronized fun beginNavigation() {
        gate.beginNavigation()
        subscription?.close()
        subscription = null
    }

    @Synchronized fun changePage(page: String?) {
        gate.changePage(page)
        if (gate.captureEvent() == null) { subscription?.close(); subscription = null }
    }

    private fun receiveEvent(ticket: RendererEventGate.Ticket, name: String, frame: JSONObject) {
        if (!gate.accepts(ticket)) return
        if (name !in protocol.descriptor.events) return
        val payload = frame.optJSONObject("data") ?: return
        val envelope = JSONObject().put("documentId", ticket.documentId)
            .put("name", name).put("payload", payload).toString()
        if (envelope.toByteArray(Charsets.UTF_8).size > 65536) return
        if (pendingEvents.incrementAndGet() > 16) {
            pendingEvents.decrementAndGet()
            throw IllegalStateException("Renderer event queue full") // Remove only this observer.
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
            service.runtime.execute {
                if (!gate.current(generation, origin!!)) return@execute
                try { synchronized(this) {
                    check(!closed && gate.current(generation, origin))
                    gate.bind(generation, origin, parsed.documentId)
                    val ticket = gate.captureEvent()
                    if (subscription == null && ticket != null) {
                        subscription = service.runtime.subscribe { event -> receiveEvent(ticket, event.name, event.frame) }
                    }
                } }
                catch (_: Exception) {
                    main.post { if (gate.current(generation, origin)) callback.failure(403, "Renderer document identity rejected") }
                    return@execute
                }
                val response = protocol.execute(parsed)
                main.post { if (!closed && gate.current(generation, origin)) callback.success(response.toString()) }
            }
        } catch (_: IllegalStateException) {
            callback.failure(503, "Sample runtime unavailable")
        } catch (_: RejectedExecutionException) {
            callback.failure(429, "Sample I/O queue is full or closed")
        }
        return true
    }

    @Synchronized override fun close() {
        closed = true
        gate.close()
        subscription?.close()
        subscription = null
    }
}
