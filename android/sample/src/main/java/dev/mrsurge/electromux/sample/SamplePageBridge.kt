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

class SamplePageBridge(context: Context) : Closeable {
    private val client = HelperClient(context)
    private val protocol = SampleConsumer.protocol(client)
    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    @Volatile private var closed = false

    fun handle(request: String, origin: String?, callback: CefriumBrowser.QueryCallback): Boolean {
        if (closed || origin !in protocol.descriptor.routes || request.toByteArray().size > 4096) {
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
                val response = protocol.execute(parsed)
                main.post { if (!closed) callback.success(response.toString()) }
            }
        } catch (_: RejectedExecutionException) {
            callback.failure(429, "Sample I/O queue is full or closed")
        }
        return true
    }

    override fun close() {
        closed = true
        client.close()
        io.shutdownNow()
    }
}
