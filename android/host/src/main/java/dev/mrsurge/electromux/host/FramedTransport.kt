package dev.mrsurge.electromux.host

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

sealed interface TransportFrame {
    data class Reply(val id: Int, val raw: String) : TransportFrame
    data class Event(val name: String, val raw: String) : TransportFrame
}

/** One stream reader, one serialized request lane; no mutation replay. */
class FramedTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeStream: () -> Unit,
    private val decode: (String) -> TransportFrame,
    eventNames: Set<String> = emptySet(),
    private val onEvent: (TransportFrame.Event) -> Unit = {},
    private val timeoutMillis: Long = 5000,
) : Closeable {
    private val allowedEvents = eventNames.toSet()
    private val gate = Any()
    private val requestLane = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val events = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))
    private var pending: Pair<Int, CompletableFuture<String>>? = null
    @Volatile private var closed = false
    private val reader = Thread({ readLoop() }, "electromux-helper-reader").apply { isDaemon = true }

    init {
        require(timeoutMillis > 0)
        require(allowedEvents.all { it.isNotEmpty() && it.length <= 128 })
        reader.start()
    }

    fun request(id: Int, raw: String): String = synchronized(requestLane) {
        val reply = CompletableFuture<String>()
        synchronized(gate) {
            check(!closed) { "Helper transport closed" }
            check(pending == null)
            pending = id to reply
        }
        val deadline = timer.schedule({ fail(IllegalStateException("Helper request deadline exceeded")) },
            timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            FrameCodec.write(output, raw)
            reply.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            fail(error)
            throw error
        } finally {
            deadline.cancel(false)
            synchronized(gate) { if (pending?.second === reply) pending = null }
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                var deadline: java.util.concurrent.ScheduledFuture<*>? = null
                val raw = try {
                    FrameCodec.read(input) {
                        deadline = timer.schedule({ fail(IllegalStateException("Partial helper frame deadline exceeded")) },
                            timeoutMillis, TimeUnit.MILLISECONDS)
                    }
                } finally { deadline?.cancel(false) }
                when (val frame = decode(raw)) {
                    is TransportFrame.Reply -> synchronized(gate) {
                        val waiter = checkNotNull(pending) { "Unsolicited helper reply" }
                        check(waiter.first == frame.id && !waiter.second.isDone) { "Helper correlation failure" }
                        waiter.second.complete(frame.raw)
                    }
                    is TransportFrame.Event -> {
                        check(frame.name in allowedEvents) { "Undeclared helper event" }
                        // Never run renderer work on the stream reader. Saturation
                        // closes this connection instead of retaining an event journal.
                        events.execute { if (!closed) {
                            try { onEvent(frame) } catch (error: Exception) { fail(error) }
                        } }
                    }
                }
            }
        } catch (error: Exception) { fail(error) }
    }

    private fun fail(error: Exception) {
        synchronized(gate) {
            if (closed) return
            closed = true
            pending?.second?.completeExceptionally(error)
        }
        try { closeStream() } catch (_: Exception) { }
        events.shutdownNow()
        timer.shutdownNow()
    }

    override fun close() { fail(IllegalStateException("Helper transport closed")) }
}
