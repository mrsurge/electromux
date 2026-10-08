package dev.mrsurge.electromux.node

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.mrsurge.electromux.host.FramedTransport
import dev.mrsurge.electromux.host.TransportFrame
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object NodeNative {
    init { System.loadLibrary("electromux_node") }
    external fun socketPair(): IntArray?
    external fun shutdownSocket(fd: Int)
    external fun start(entry: String, home: String, temp: String, fd: Int): Int
}

/** One engine per dedicated Android service process. Never Activity-owned. */
class EmbeddedNodeRuntime(context: Context, onEvent: (String) -> Unit) : Closeable {
    private val ready = CompletableFuture<Unit>()
    private val sequence = AtomicInteger()
    private val transport: FramedTransport
    init {
        val root = File(context.noBackupFilesDir, "embedded-node-proof").apply {
            check(isDirectory || mkdirs())
        }
        val entry = File(root, "main.mjs")
        val temporary = File.createTempFile("runtime-", ".mjs", root)
        try {
            context.assets.open("embedded_node/main.mjs").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(entry))
        } finally { temporary.delete() }
        val pair = checkNotNull(NodeNative.socketPair()) { "Could not create private Node IPC" }
        val host = ParcelFileDescriptor.adoptFd(pair[0])
        val input = FileInputStream(host.fileDescriptor)
        val output = FileOutputStream(host.fileDescriptor)
        transport = FramedTransport(input, output, {
            NodeNative.shutdownSocket(pair[0]); host.close()
            ready.completeExceptionally(IllegalStateException("Node channel closed"))
        }, { raw ->
            val frame = JSONObject(raw)
            if (frame.has("event")) {
                check(!frame.has("id") && frame.opt("event") is String)
                TransportFrame.Event(frame.getString("event"), raw)
            } else {
                val id = frame.opt("id"); check(id is Int)
                TransportFrame.Reply(id, raw)
            }
        }, setOf("runtime.ready", "sample.updated"), { frame ->
            if (frame.name == "runtime.ready") {
                val data = JSONObject(frame.raw).getJSONObject("data")
                check(data.getInt("version") == 1 && data.getString("node").startsWith("v24."))
                ready.complete(Unit)
            } else onEvent(frame.raw)
        })
        Thread({
            try {
                val code = NodeNative.start(entry.absolutePath, root.absolutePath, context.cacheDir.absolutePath, pair[1])
                ready.completeExceptionally(IllegalStateException("Node exited ($code); process restart required"))
            } catch (error: Exception) { ready.completeExceptionally(error) }
            finally { transport.close() }
        }, "electromux-node-engine").start()
    }
    fun request(method: String): String {
        require(method == "ping" || method == "file.proof")
        ready.get(8, TimeUnit.SECONDS)
        val id = sequence.incrementAndGet()
        check(id > 0)
        return transport.request(id, JSONObject().put("id", id).put("method", method).toString())
    }
    override fun close() { transport.close() }
}
