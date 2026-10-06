package dev.mrsurge.electromux.sample

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Called only on the host's single bounded I/O lane. Never retries mutations. */
class HelperClient(context: Context) : Closeable {
    private val provisioner = SampleProvisioner(context.applicationContext)
    private val launcher = TermuxLaunchAdapter(context.applicationContext)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: LocalSocket? = null
    private var nextId = 1

    fun connect(): JSONObject {
        if (socket != null) return call("status")
        check(launcher.inspect().canLaunch) { "Termux identity unavailable" }
        val session = provisioner.prepare()
        if (!File(session.socketPath).exists()) {
            check(!provisioner.attempted()) { "Previous launch outcome unknown; refusing duplicate launch" }
            provisioner.markAttempted(true)
            launcher.launchHelper(session.launchSpec())
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!File(session.socketPath).exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        }
        check(File(session.socketPath).exists()) { "Helper socket not ready" }
        val connection = LocalSocket()
        socket = connection
        try {
            bounded {
                connection.connect(LocalSocketAddress(session.socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                connection.soTimeout = 5000
                val id = nextId++
                exchange(JSONObject().put("id", id).put("method", "hello").put("version", 1)
                    .put("session", session.id).put("token", session.token))
            }
            return call("status")
        } catch (error: Exception) { disconnect(); throw error }
    }

    fun call(method: String, value: String = ""): JSONObject {
        check(socket != null) { "Connect the helper first" }
        val id = nextId++
        val request = JSONObject().put("id", id).put("method", if (method == "ping") "request" else method)
        if (method == "ping") request.put("payload", JSONObject().put("id", id).put("method", "ping").put("value", value))
        try {
            val response = bounded { exchange(request) }
            if (method == "detach" || method == "shutdown") disconnect()
            if (method == "shutdown") provisioner.markAttempted(false)
            return response
        } catch (error: Exception) { disconnect(); throw error }
    }

    private fun exchange(request: JSONObject): JSONObject {
        val connection = checkNotNull(socket)
        FrameCodec.write(connection.outputStream, request.toString())
        val reply = JSONObject(FrameCodec.read(connection.inputStream))
        check(reply.opt("id") == request.opt("id")) { "Helper response correlation failed" }
        check(!reply.has("error") && reply.has("result")) { "Helper rejected request" }
        return reply.getJSONObject("result")
    }

    private fun <T> bounded(operation: () -> T): T {
        val connection = checkNotNull(socket)
        val deadline = timer.schedule({ try { connection.close() } catch (_: Exception) { } }, 5, TimeUnit.SECONDS)
        return try { operation() } finally { deadline.cancel(false) }
    }

    fun disconnect() {
        val old = socket
        socket = null
        try { old?.close() } catch (_: Exception) { }
    }
    override fun close() { disconnect(); timer.shutdownNow() }
}
