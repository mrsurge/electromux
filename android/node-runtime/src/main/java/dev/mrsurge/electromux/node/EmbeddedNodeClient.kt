package dev.mrsurge.electromux.node

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit internal Binder connection. Detach does not stop the engine or owned child. */
class EmbeddedNodeClient(context: Context, serviceClass: Class<out EmbeddedNodeService>,
    private val onEvent: (String) -> Unit) : Closeable {
    private val context = context.applicationContext
    private val intent = Intent(this.context, serviceClass)
    private val ready = CompletableFuture<INodeHost>()
    @Volatile private var closed = false
    private var bound = false
    private var host: INodeHost? = null
    private val observer = object : INodeEvents.Stub() {
        override fun event(frame: String) {
            if (!closed && frame.toByteArray(Charsets.UTF_8).size <= 65536) onEvent(frame)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(this@EmbeddedNodeClient) {
                if (closed) return
                try {
                    val connected = INodeHost.Stub.asInterface(binder)
                    connected.subscribe(observer)
                    host = connected
                    ready.complete(connected)
                } catch (failure: Exception) { ready.completeExceptionally(failure) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            ready.completeExceptionally(IllegalStateException("Node process disconnected; explicit recovery required"))
            close()
        }
        override fun onNullBinding(name: ComponentName) {
            ready.completeExceptionally(IllegalStateException("Node binding unavailable")); close()
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    }
    fun request(method: String, params: JSONObject): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Node requests must not block Android UI" }
        synchronized(this) {
            check(!closed)
            if (!bound) {
                // Retain the service independently of its transient page/service consumer.
                context.startService(intent)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound) { "Could not bind private Node service" }
            }
        }
        val connected = ready.get(8, TimeUnit.SECONDS)
        check(!closed)
        // No uncertain mutation replay after Binder/engine failure.
        return JSONObject(connected.request(method, params.toString()))
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { host?.unsubscribe(observer) } catch (_: android.os.RemoteException) { }
        host = null
        if (bound) { context.unbindService(connection); bound = false }
        ready.completeExceptionally(IllegalStateException("Node client detached"))
    }
}
