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
    external fun start(entry: String, home: String, temp: String, fd: Int, termux: Boolean): Int
}

/** One engine per dedicated Android service process. Never Activity-owned. */
class EmbeddedNodeRuntime(context: Context, private val termux: Boolean = false,
    private val consumer: EmbeddedConsumerSpec = EmbeddedConsumerSpec.proof(termux), onEvent: (String) -> Unit) : Closeable {
    private val ready = CompletableFuture<Unit>()
    private val sequence = AtomicInteger()
    private val transport: FramedTransport
    init {
        val root = File(context.noBackupFilesDir, "embedded-node-proof").apply {
            check(isDirectory || mkdirs())
        }
        val entry = File(root, "main.mjs")
        var resourceBytes = 0L
        for (asset in consumer.resources) {
            val destination = File(root, asset)
            check(destination.canonicalPath.startsWith(root.canonicalPath + File.separator))
            val parent = checkNotNull(destination.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            val staging = File.createTempFile("resource-", ".tmp", parent)
            try {
                context.assets.open(asset).use { input -> staging.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        resourceBytes += count; check(resourceBytes <= 64L * 1024 * 1024) { "Resource byte limit" }
                        output.write(buffer, 0, count)
                    }
                } }
                check(staging.renameTo(destination))
            } finally { staging.delete() }
        }
        val temporary = File.createTempFile("runtime-", ".mjs", root)
        try {
            context.assets.open(consumer.entryAsset).use { input ->
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
        }, consumer.events + "runtime.ready", { frame ->
            if (frame.name == "runtime.ready") {
                val data = JSONObject(frame.raw).getJSONObject("data")
                check(data.getInt("version") == 1 && data.getString("node").startsWith("v24."))
                ready.complete(Unit)
            } else onEvent(frame.raw)
        })
        Thread({
            try {
                val code = NodeNative.start(entry.absolutePath, root.absolutePath, context.cacheDir.absolutePath, pair[1], termux)
                ready.completeExceptionally(IllegalStateException("Node exited ($code); process restart required"))
            } catch (error: Exception) { ready.completeExceptionally(error) }
            finally { transport.close() }
        }, "electromux-node-engine").start()
    }
    fun request(method: String, params: JSONObject? = null): String {
        require(method in consumer.methods)
        ready.get(8, TimeUnit.SECONDS)
        val id = sequence.incrementAndGet()
        check(id > 0)
        val frame = JSONObject().put("id", id).put("method", method)
        if (params != null) frame.put("params", JSONObject(params.toString()))
        return transport.request(id, frame.toString())
    }
    /** Electron's opt-in ACK lane must not wait behind the request it completes. */
    fun acknowledgeEffect(id: Int, error: String? = null) {
        check("electron.effect" in consumer.events) { "Consumer does not declare Electron effects" }
        require(id > 0 && (error == null || error.length in 1..512))
        val frame = JSONObject().put("ack", id)
        if (error != null) frame.put("error", error)
        transport.sendControl(frame.toString())
    }
    override fun close() { transport.close() }
}
