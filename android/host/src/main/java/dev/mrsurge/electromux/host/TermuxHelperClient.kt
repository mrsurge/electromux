package dev.mrsurge.electromux.host

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.mrsurge.electromux.host.FrameCodec
import dev.mrsurge.electromux.host.FramedTransport
import dev.mrsurge.electromux.host.TransportFrame
import dev.mrsurge.electromux.host.HelperProvisioner
import dev.mrsurge.electromux.host.HelperInstallSpec

/** Called only on the host's single bounded I/O lane. Never retries mutations. */
class TermuxHelperClient(context: Context, installSpec: HelperInstallSpec,
                   private val eventNames: Set<String> = emptySet(),
                   private val onEvent: (String, JSONObject) -> Unit = { _, _ -> }) : Closeable {
    private val provisioner = HelperProvisioner(context.applicationContext, installSpec)
    private val launcher = TermuxHelperLauncher(context.applicationContext)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var transport: FramedTransport? = null
    private var nextId = 1
    @Volatile private var closed = false

    fun connect(): JSONObject {
        check(!closed) { "Helper client closed" }
        if (socket != null) return call("status")
        check(launcher.inspect().canLaunch) { "Termux identity unavailable" }
        val session = provisioner.prepare()
        check(!closed) { "Helper client closed" }
        if (!File(session.socketPath).exists()) {
            check(!provisioner.attempted()) { "Previous launch outcome unknown; refusing duplicate launch" }
            provisioner.markAttempted(true)
            launcher.launch(LaunchSpec("/data/data/com.termux/files/usr/bin/python",
                session.arguments(), session.packageRoot.path))
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!closed && !File(session.socketPath).exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        }
        check(!closed) { "Helper client closed" }
        check(File(session.socketPath).exists()) { "Helper socket not ready" }
        val connection = connectEndpoint(session)
        synchronized(this) {
            if (closed) { connection.close(); error("Helper client closed") }
            socket = connection
        }
        try {
            check(!closed) { "Helper client closed" }
            bounded {
                connection.soTimeout = 5000
                val id = nextId++
                exchange(JSONObject().put("id", id).put("method", "hello").put("version", 1)
                    .put("session", session.id).put("token", session.token)
                    .put("events", eventNames.isNotEmpty()))
            }
            connection.soTimeout = 0 // Reader permits idle; partial frames remain bounded.
            check(!closed) { "Helper client closed" }
            val nextTransport = FramedTransport(connection.inputStream, connection.outputStream,
                { try { connection.close() } finally {
                    if (socket === connection) socket = null
                } }, { raw ->
                    val frame = JSONObject(raw)
                    if (frame.has("event")) {
                        check(!frame.has("id") && frame.opt("event") is String)
                        TransportFrame.Event(frame.getString("event"), raw)
                    } else {
                        val id = frame.opt("id")
                        check(id is Int)
                        TransportFrame.Reply(id, raw)
                    }
                }, eventNames, { frame -> onEvent(frame.name, JSONObject(frame.raw)) })
            synchronized(this) {
                if (closed || socket !== connection) { nextTransport.close(); error("Helper client closed") }
                transport = nextTransport
            }
            return call("status")
        } catch (error: Exception) { disconnect(); throw error }
    }

    private fun connectEndpoint(session: HelperSession): LocalSocket {
        var recoveryDispatched = false
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (true) {
            check(!closed) { "Helper client closed" }
            val connection = LocalSocket()
            try {
                connection.connect(LocalSocketAddress(session.socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                return connection
            } catch (error: IOException) {
                connection.close()
                // Android LocalSocket exposes the native connect errno as IOException text.
                // No recovery for handshake/auth/timeouts or requests whose outcome is unknown.
                if (error.message != "Connection refused" &&
                    !(recoveryDispatched && error.message == "No such file or directory")) throw error
                if (!recoveryDispatched) {
                    recoveryDispatched = true
                    launcher.launch(LaunchSpec("/data/data/com.termux/files/usr/bin/python",
                        session.arguments() + "--recover-stale", session.packageRoot.path))
                }
                if (SystemClock.elapsedRealtime() >= deadline) throw error
                Thread.sleep(50)
            }
        }
    }

    fun call(method: String): JSONObject {
        require(method in setOf("status", "start", "stop", "detach", "shutdown"))
        return send(method, null)
    }

    /** Consumer policy validates application methods; host supplies correlation. */
    fun requestBackend(payload: JSONObject): JSONObject = send("request", payload)

    private fun send(method: String, payload: JSONObject?): JSONObject {
        check(!closed) { "Helper client closed" }
        check(socket != null) { "Connect the helper first" }
        val id = nextId++
        val request = JSONObject().put("id", id).put("method", method)
        if (payload != null) request.put("payload", JSONObject(payload.toString()).put("id", id))
        try {
            val reply = JSONObject(checkNotNull(transport).request(id, request.toString()))
            check(!reply.has("error") && reply.has("result")) { "Helper rejected request" }
            val response = reply.getJSONObject("result")
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

    @Synchronized fun disconnect() {
        val oldTransport = transport
        transport = null
        oldTransport?.close()
        val old = socket
        socket = null
        try { old?.close() } catch (_: Exception) { }
    }
    @Synchronized override fun close() { closed = true; disconnect(); timer.shutdownNow() }
}
