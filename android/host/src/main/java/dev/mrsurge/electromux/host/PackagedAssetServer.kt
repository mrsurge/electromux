package dev.mrsurge.electromux.host

import android.content.res.AssetManager
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Loopback HTTP semantics for APK assets, without file-origin/CORS bypasses. */
class PackagedAssetServer(private val assets: AssetManager, private val root: String) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    val origin = "http://127.0.0.1:${server.localPort}"
    init {
        require(root.matches(Regex("[A-Za-z0-9_-]+")))
        Thread({
            while (!server.isClosed) try {
                val socket = server.accept()
                try { workers.execute { serve(socket) } } catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
            } catch (_: Exception) { if (!server.isClosed) close() }
        }, "electromux-assets").apply { isDaemon = true; start() }
    }
    private fun serve(socket: Socket) = socket.use {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                check(header.length < 16384)
                val byte = input.read(); check(byte >= 0); header.append(byte.toChar())
            }
            val request = header.toString().substringBefore("\r\n").split(' ')
            check(request.size == 3 && request[0] in setOf("GET", "HEAD"))
            val path = URLDecoder.decode(request[1].substringBefore('?'), "UTF-8").removePrefix("/")
            check(path.isNotEmpty() && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." })
            val content = try { assets.open("$root/$path") } catch (_: java.io.IOException) { null }
            val output = socket.getOutputStream()
            if (content == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else content.use { body ->
                val mime = when (path.substringAfterLast('.').lowercase()) {
                    "html" -> "text/html; charset=utf-8"
                    "js", "mjs" -> "application/javascript; charset=utf-8"
                    "json" -> "application/json; charset=utf-8"
                    "css" -> "text/css; charset=utf-8"
                    "png" -> "image/png"
                    "svg" -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
                output.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n".toByteArray())
                if (request[0] == "GET") body.copyTo(output)
            }
            output.flush()
        } catch (_: Exception) { /* Malformed/closed requests never access another root. */ }
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
