package dev.mrsurge.electromux.host

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Service-owned request lane; renderer subscriptions never own the transport. */
class RuntimeOwner<T>(private val release: () -> Unit) : Closeable {
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    private val observers = linkedMapOf<Long, (T) -> Unit>()
    private var sequence = 0L
    private var closed = false

    @Synchronized fun execute(action: () -> Unit) {
        check(!closed) { "Runtime closed" }
        io.execute(action)
    }

    @Synchronized fun subscribe(observer: (T) -> Unit): Closeable {
        check(!closed && observers.size < 16) { "Runtime subscription limit" }
        val id = ++sequence
        observers[id] = observer
        return Closeable { synchronized(this) { observers.remove(id) } }
    }

    @Synchronized fun emit(event: T) {
        if (closed) return
        // Callbacks must be nonblocking posts. Holding the monitor makes unsubscribe
        // a fence: no invocation can begin after it returns.
        observers.toMap().forEach { (id, observer) ->
            if (observers[id] === observer) {
                try { observer(event) } catch (_: Exception) { observers.remove(id) }
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            observers.clear()
        }
        io.shutdownNow()
        release()
    }
}
